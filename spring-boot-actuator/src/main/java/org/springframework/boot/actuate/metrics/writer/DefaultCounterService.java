/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.actuate.metrics.writer;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.actuate.metrics.CounterService;
import org.springframework.util.Assert;

/**
 * Default implementation of {@link CounterService}.
 *
 * @author Dave Syer
 */
public class DefaultCounterService implements CounterService {

	private final MetricWriter writer;

	private final ConcurrentHashMap<String, String> names = new ConcurrentHashMap<>();

	/**
	 * Create a {@link DefaultCounterService} instance.
	 * @param writer the underlying writer used to manage metrics
	 */
	public DefaultCounterService(MetricWriter writer) {
		this.writer = writer;
	}

	@Override
	public void increment(String metricName) {
		this.writer.increment(new Delta<>(wrap(metricName), 1L));
	}

	@Override
	public void increment(String metricName, long delta) {
		Assert.state(delta > 0, "Delta should be greater than 0");
		this.writer.increment(new Delta<>(wrap(metricName), delta));
	}

	@Override
	public void decrement(String metricName) {
		this.writer.increment(new Delta<>(wrap(metricName), -1L));
	}

	@Override
	public void decrement(String metricName, long delta) {
		Assert.state(delta > 0, "Delta should be greater than 0");
		this.writer.increment(new Delta<>(wrap(metricName), -delta));
	}

	@Override
	public void reset(String metricName) {
		this.writer.reset(wrap(metricName));
	}

	private String wrap(String metricName) {
		String cached = this.names.get(metricName);
		if (cached != null) {
			return cached;
		}
		if (metricName.startsWith("counter.") || metricName.startsWith("meter.")) {
			return metricName;
		}
		String name = "counter." + metricName;
		this.names.put(metricName, name);
		return name;
	}

}
