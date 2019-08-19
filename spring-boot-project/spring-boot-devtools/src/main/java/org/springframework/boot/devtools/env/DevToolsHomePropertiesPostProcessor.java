/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.boot.devtools.env;

import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.devtools.DevToolsEnablementDeducer;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.util.StringUtils;

/**
 * {@link EnvironmentPostProcessor} to add devtools properties from the user's home
 * folder.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 * @author HaiTao Zhang
 * @since 1.3.0
 */
public class DevToolsHomePropertiesPostProcessor implements EnvironmentPostProcessor {

	private static final String[] FILE_NAMES = new String[] { ".spring-boot-devtools.yml", ".spring-boot-devtools.yaml",
			".spring-boot-devtools.properties" };

	private Logger logger = Logger.getLogger(getClass().getName());

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (DevToolsEnablementDeducer.shouldEnable(Thread.currentThread())) {
			File home = getHomeFolder();
			Properties properties = processDir(home, "/.config/spring-boot/", environment);
			if (properties.isEmpty()) {
				processDir(home, "", environment);
			}
		}
	}

	private Properties processDir(File home, String configPath, ConfigurableEnvironment environment) {
		Properties properties = new Properties();
		for (String fileName : FILE_NAMES) {
			File propertyFile = (home != null) ? new File(home, configPath + fileName) : null;
			if (propertyFile != null && propertyFile.exists() && propertyFile.isFile()) {
				addProperty(propertyFile, environment, fileName, properties);
			}
		}
		return properties;
	}

	private void addProperty(File propertyFile, ConfigurableEnvironment environment, String fileName,
			Properties properties) {
		FileSystemResource resource = new FileSystemResource(propertyFile);
		try {
			PropertiesLoaderUtils.fillProperties(properties, resource);
			environment.getPropertySources().addFirst(new PropertiesPropertySource("devtools-local", properties));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to load " + fileName, ex);
		}
	}

	protected File getHomeFolder() {
		String home = System.getProperty("user.home");
		if (StringUtils.hasLength(home)) {
			return new File(home);
		}
		return null;
	}

}
