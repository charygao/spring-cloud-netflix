/*
 * Copyright 2013-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.eureka;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import reactor.core.publisher.Mono;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.cloud.client.discovery.health.DiscoveryCompositeHealthContributor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.Lifecycle;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * A Eureka health checker, maps the application status into {@link InstanceStatus} that
 * will be propagated to Eureka registry.
 *
 * On each heartbeat Eureka performs the health check invoking registered
 * {@link HealthCheckHandler}. By default this implementation will perform aggregation of
 * all registered {@link HealthIndicator} through registered {@link StatusAggregator}.
 *
 * A {@code null} status is returned when the application context is closed (or in the
 * process of being closed). This prevents Eureka from updating the health status and only
 * consider the status present in the current InstanceInfo.
 *
 * @author Jakub Narloch
 * @author Spencer Gibb
 * @author Nowrin Anwar Joyita
 * @author Bertrand Renuart
 * @see HealthCheckHandler
 * @see StatusAggregator
 */
public class EurekaHealthCheckHandler
		implements HealthCheckHandler, ApplicationContextAware, InitializingBean, Ordered, Lifecycle {

	private static final Map<Status, InstanceInfo.InstanceStatus> STATUS_MAPPING = new HashMap<Status, InstanceInfo.InstanceStatus>() {
		{
			put(Status.UNKNOWN, InstanceStatus.UNKNOWN);
			put(Status.OUT_OF_SERVICE, InstanceStatus.OUT_OF_SERVICE);
			put(Status.DOWN, InstanceStatus.DOWN);
			put(Status.UP, InstanceStatus.UP);
		}
	};

	private StatusAggregator statusAggregator;

	private ApplicationContext applicationContext;

	private Map<String, HealthIndicator> healthIndicators = new HashMap<>();

	/**
	 * {@code true} until the context is stopped.
	 */
	private boolean running = true;

	private Map<String, ReactiveHealthIndicator> reactiveHealthIndicators = new HashMap<>();

	public EurekaHealthCheckHandler(StatusAggregator statusAggregator) {
		this.statusAggregator = statusAggregator;
		Assert.notNull(statusAggregator, "StatusAggregator must not be null");

	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	@Override
	public void afterPropertiesSet() {
		final Map<String, HealthIndicator> healthIndicators = applicationContext.getBeansOfType(HealthIndicator.class);
		final Map<String, ReactiveHealthIndicator> reactiveHealthIndicators = applicationContext
				.getBeansOfType(ReactiveHealthIndicator.class);

		populateHealthIndicators(healthIndicators);
		populateReactiveHealthIndicators(reactiveHealthIndicators);
	}

	void populateHealthIndicators(Map<String, HealthIndicator> healthIndicators) {
		for (Map.Entry<String, HealthIndicator> entry : healthIndicators.entrySet()) {
			// ignore EurekaHealthIndicator and flatten the rest of the composite
			// otherwise there is a never ending cycle of down. See gh-643
			if (entry.getValue() instanceof DiscoveryCompositeHealthContributor) {
				DiscoveryCompositeHealthContributor indicator = (DiscoveryCompositeHealthContributor) entry.getValue();
				indicator.forEach(contributor -> {
					if (!(contributor.getContributor() instanceof EurekaHealthIndicator)) {
						this.healthIndicators.put(contributor.getName(),
								(HealthIndicator) contributor.getContributor());
					}
				});
			}
			else {
				this.healthIndicators.put(entry.getKey(), entry.getValue());
			}
		}
	}

	void populateReactiveHealthIndicators(Map<String, ReactiveHealthIndicator> reactiveHealthIndicators) {
		for (Map.Entry<String, ReactiveHealthIndicator> entry : reactiveHealthIndicators.entrySet()) {
			this.reactiveHealthIndicators.put(entry.getKey(), entry.getValue());
		}
	}

	@Override
	public InstanceStatus getStatus(InstanceStatus instanceStatus) {
		if (running) {
			return getHealthStatus();
		}
		else {
			// Return nothing if the context is not running, so the status held by the
			// InstanceInfo remains unchanged.
			// See gh-1571
			return null;
		}
	}

	protected InstanceStatus getHealthStatus() {
		Status status = getStatus(statusAggregator);
		return mapToInstanceStatus(status);
	}

	protected Status getStatus(StatusAggregator statusAggregator) {
		Status status;

		Set<Status> statusSet = new HashSet<>();
		if (healthIndicators != null) {
			statusSet.addAll(healthIndicators.values().stream().map(HealthIndicator::health).map(Health::getStatus)
					.collect(Collectors.toSet()));
		}

		if (reactiveHealthIndicators != null) {
			statusSet.addAll(reactiveHealthIndicators.values().stream().map(ReactiveHealthIndicator::health)
					.map(Mono::block).filter(Objects::nonNull).map(Health::getStatus).collect(Collectors.toSet()));
		}

		status = statusAggregator.getAggregateStatus(statusSet);
		return status;
	}

	protected InstanceStatus mapToInstanceStatus(Status status) {
		if (!STATUS_MAPPING.containsKey(status)) {
			return InstanceStatus.UNKNOWN;
		}
		return STATUS_MAPPING.get(status);
	}

	@Override
	public int getOrder() {
		// registered with a high order priority so the close() method is invoked early
		// and *BEFORE* EurekaAutoServiceRegistration
		// (must be in effect when the registration is closed and the eureka replication
		// triggered -> health check handler is
		// consulted at that moment)
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		running = false;
	}

	@Override
	public boolean isRunning() {
		return true;
	}

}
