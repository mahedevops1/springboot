/*
 * Copyright 2012-2016 the original author or authors.
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

package org.springframework.boot.actuate.autoconfigure;

import java.io.FileNotFoundException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Vector;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.springframework.boot.actuate.endpoint.Endpoint;
import org.springframework.boot.actuate.endpoint.mvc.EndpointHandlerMapping;
import org.springframework.boot.actuate.endpoint.mvc.EndpointHandlerMappingCustomizer;
import org.springframework.boot.actuate.endpoint.mvc.EnvironmentMvcEndpoint;
import org.springframework.boot.actuate.endpoint.mvc.HalJsonMvcEndpoint;
import org.springframework.boot.actuate.endpoint.mvc.HealthMvcEndpoint;
import org.springframework.boot.actuate.endpoint.mvc.MetricsMvcEndpoint;
import org.springframework.boot.actuate.endpoint.mvc.MvcEndpoint;
import org.springframework.boot.actuate.endpoint.mvc.ShutdownMvcEndpoint;
import org.springframework.boot.autoconfigure.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.EmbeddedServletContainerAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ErrorMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.web.WebMvcAutoConfiguration;
import org.springframework.boot.context.embedded.AnnotationConfigEmbeddedWebApplicationContext;
import org.springframework.boot.context.embedded.ConfigurableEmbeddedServletContainer;
import org.springframework.boot.context.embedded.EmbeddedServletContainer;
import org.springframework.boot.context.embedded.EmbeddedServletContainerException;
import org.springframework.boot.context.embedded.EmbeddedServletContainerFactory;
import org.springframework.boot.context.embedded.EmbeddedServletContainerInitializedEvent;
import org.springframework.boot.context.embedded.ServerPortInfoApplicationContextInitializer;
import org.springframework.boot.context.embedded.tomcat.TomcatEmbeddedServletContainerFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.test.util.EnvironmentTestUtils;
import org.springframework.boot.testutil.Matched;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.SocketUtils;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link EndpointWebMvcAutoConfiguration}.
 *
 * @author Phillip Webb
 * @author Greg Turnquist
 * @author Andy Wilkinson
 * @author Eddú Meléndez
 */
public class EndpointWebMvcAutoConfigurationTests {

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	private final AnnotationConfigEmbeddedWebApplicationContext applicationContext = new AnnotationConfigEmbeddedWebApplicationContext();

	private static ThreadLocal<Ports> ports = new ThreadLocal<Ports>();

	private static ServerProperties server = new ServerProperties();

	private static ManagementServerProperties management = new ManagementServerProperties();

	@Before
	public void grabPorts() {
		Ports values = new Ports();
		ports.set(values);
		server.setPort(values.server);
		management.setPort(values.management);
	}

	@After
	public void close() {
		this.applicationContext.close();
	}

	@Test
	public void onSamePort() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				BaseConfiguration.class, ServerPortConfig.class,
				EndpointWebMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/endpoint", ports.get().server, "endpointoutput");
		assertContent("/controller", ports.get().management, null);
		assertContent("/endpoint", ports.get().management, null);
		assertThat(hasHeader("/endpoint", ports.get().server, "X-Application-Context"))
				.isTrue();
		assertThat(this.applicationContext.containsBean("applicationContextIdFilter"))
				.isTrue();
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onSamePortWithoutHeader() throws Exception {
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"management.add-application-context-header:false");
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				BaseConfiguration.class, ServerPortConfig.class,
				EndpointWebMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertThat(hasHeader("/endpoint", ports.get().server, "X-Application-Context"))
				.isFalse();
		assertThat(this.applicationContext.containsBean("applicationContextIdFilter"))
				.isFalse();
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onDifferentPort() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				DifferentPortConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/endpoint", ports.get().server, null);
		assertContent("/controller", ports.get().management, null);
		assertContent("/endpoint", ports.get().management, "endpointoutput");
		assertContent("/error", ports.get().management, startsWith("{"));
		ApplicationContext managementContext = this.applicationContext
				.getBean(ManagementContextResolver.class).getApplicationContext();
		List<?> interceptors = (List<?>) ReflectionTestUtils.getField(
				managementContext.getBean(EndpointHandlerMapping.class), "interceptors");
		assertThat(interceptors).hasSize(1);
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onDifferentPortManagementSslDisabled() throws Exception {
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"management.ssl.enabled:false");
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				DifferentPortConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/endpoint", ports.get().server, null);
		assertContent("/controller", ports.get().management, null);
		assertContent("/endpoint", ports.get().management, "endpointoutput");
		assertContent("/error", ports.get().management, startsWith("{"));
		ApplicationContext managementContext = this.applicationContext
				.getBean(ManagementContextResolver.class).getApplicationContext();
		List<?> interceptors = (List<?>) ReflectionTestUtils.getField(
				managementContext.getBean(EndpointHandlerMapping.class), "interceptors");
		assertThat(interceptors).hasSize(1);
		ManagementServerProperties managementServerProperties = this.applicationContext
				.getBean(ManagementServerProperties.class);
		assertThat(managementServerProperties.getSsl()).isNotNull();
		assertThat(managementServerProperties.getSsl().isEnabled()).isFalse();
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onDifferentPortWithSpecificContainer() throws Exception {
		this.applicationContext.register(SpecificContainerConfig.class, RootConfig.class,
				DifferentPortConfig.class, EndpointConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/endpoint", ports.get().server, null);
		assertContent("/controller", ports.get().management, null);
		assertContent("/endpoint", ports.get().management, "endpointoutput");
		assertContent("/error", ports.get().management, startsWith("{"));
		ApplicationContext managementContext = this.applicationContext
				.getBean(ManagementContextResolver.class).getApplicationContext();
		List<?> interceptors = (List<?>) ReflectionTestUtils.getField(
				managementContext.getBean(EndpointHandlerMapping.class), "interceptors");
		assertThat(interceptors).hasSize(1);
		EmbeddedServletContainerFactory parentContainerFactory = this.applicationContext
				.getBean(EmbeddedServletContainerFactory.class);
		EmbeddedServletContainerFactory managementContainerFactory = managementContext
				.getBean(EmbeddedServletContainerFactory.class);
		assertThat(parentContainerFactory)
				.isInstanceOf(SpecificEmbeddedServletContainerFactory.class);
		assertThat(managementContainerFactory)
				.isInstanceOf(SpecificEmbeddedServletContainerFactory.class);
		assertThat(managementContainerFactory).isNotSameAs(parentContainerFactory);
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onDifferentPortAndContext() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				DifferentPortConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class);
		management.setContextPath("/admin");
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/admin/endpoint", ports.get().management, "endpointoutput");
		assertContent("/error", ports.get().management, startsWith("{"));
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onDifferentPortAndMainContext() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				DifferentPortConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class);
		management.setContextPath("/admin");
		server.setContextPath("/spring");
		this.applicationContext.refresh();
		assertContent("/spring/controller", ports.get().server, "controlleroutput");
		assertContent("/admin/endpoint", ports.get().management, "endpointoutput");
		assertContent("/error", ports.get().management, startsWith("{"));
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onDifferentPortWithoutErrorMvcAutoConfiguration() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				DifferentPortConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/error", ports.get().management, null);
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onDifferentPortInServletContainer() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				DifferentPortConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class);
		ServletContext servletContext = mock(ServletContext.class);
		given(servletContext.getInitParameterNames())
				.willReturn(new Vector<String>().elements());
		given(servletContext.getAttributeNames())
				.willReturn(new Vector<String>().elements());
		this.applicationContext.setServletContext(servletContext);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().management, null);
		assertContent("/endpoint", ports.get().management, null);
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void onRandomPort() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				RandomPortConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class);
		GrabManagementPort grabManagementPort = new GrabManagementPort(
				this.applicationContext);
		this.applicationContext.addApplicationListener(grabManagementPort);
		this.applicationContext.refresh();
		int managementPort = grabManagementPort.getServletContainer().getPort();
		assertThat(managementPort).isNotEqualTo(ports.get().server);
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/endpoint", ports.get().server, null);
		assertContent("/controller", managementPort, null);
		assertContent("/endpoint", managementPort, "endpointoutput");
	}

	@Test
	public void onDifferentPortWithPrimaryFailure() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				DifferentPortConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class, ErrorMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		ApplicationContext managementContext = this.applicationContext
				.getBean(ManagementContextResolver.class).getApplicationContext();
		ApplicationFailedEvent event = mock(ApplicationFailedEvent.class);
		given(event.getApplicationContext()).willReturn(this.applicationContext);
		this.applicationContext.publishEvent(event);
		assertThat(((ConfigurableApplicationContext) managementContext).isActive())
				.isFalse();
		this.applicationContext.close();
	}

	@Test
	public void disabled() throws Exception {
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				DisableConfig.class, BaseConfiguration.class,
				EndpointWebMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/endpoint", ports.get().server, null);
		assertContent("/controller", ports.get().management, null);
		assertContent("/endpoint", ports.get().management, null);
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void specificPortsViaProperties() throws Exception {
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"server.port:" + ports.get().server,
				"management.port:" + ports.get().management);
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				BaseConfiguration.class, EndpointWebMvcAutoConfiguration.class,
				ErrorMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/endpoint", ports.get().server, null);
		assertContent("/controller", ports.get().management, null);
		assertContent("/endpoint", ports.get().management, "endpointoutput");
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void specificPortsViaPropertiesWithClash() throws Exception {
		int managementPort = ports.get().management;
		ServerSocket serverSocket = new ServerSocket();
		serverSocket.bind(new InetSocketAddress(managementPort));
		try {
			EnvironmentTestUtils.addEnvironment(this.applicationContext,
					"server.port:" + ports.get().server,
					"management.port:" + ports.get().management);
			this.applicationContext.register(RootConfig.class, EndpointConfig.class,
					BaseConfiguration.class, EndpointWebMvcAutoConfiguration.class,
					ErrorMvcAutoConfiguration.class);
			this.thrown.expect(EmbeddedServletContainerException.class);
			this.applicationContext.refresh();
			this.applicationContext.close();
		}
		finally {
			serverSocket.close();
			assertAllClosed();
		}
	}

	@Test
	public void contextPath() throws Exception {
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"management.contextPath:/test");
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				ServerPortConfig.class, PropertyPlaceholderAutoConfiguration.class,
				ManagementServerPropertiesAutoConfiguration.class,
				ServerPropertiesAutoConfiguration.class, JacksonAutoConfiguration.class,
				EmbeddedServletContainerAutoConfiguration.class,
				HttpMessageConvertersAutoConfiguration.class,
				DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
				EndpointWebMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		assertContent("/test/endpoint", ports.get().server, "endpointoutput");
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void overrideServerProperties() throws Exception {
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"server.displayName:foo");
		this.applicationContext.register(RootConfig.class, EndpointConfig.class,
				ServerPortConfig.class, PropertyPlaceholderAutoConfiguration.class,
				ManagementServerPropertiesAutoConfiguration.class,
				ServerPropertiesAutoConfiguration.class, JacksonAutoConfiguration.class,
				EmbeddedServletContainerAutoConfiguration.class,
				HttpMessageConvertersAutoConfiguration.class,
				DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
				EndpointWebMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		assertContent("/controller", ports.get().server, "controlleroutput");
		ServerProperties serverProperties = this.applicationContext
				.getBean(ServerProperties.class);
		assertThat(serverProperties.getDisplayName()).isEqualTo("foo");
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void portPropertiesOnSamePort() throws Exception {
		this.applicationContext.register(RootConfig.class, BaseConfiguration.class,
				ServerPortConfig.class, EndpointWebMvcAutoConfiguration.class);
		new ServerPortInfoApplicationContextInitializer()
				.initialize(this.applicationContext);
		this.applicationContext.refresh();
		Integer localServerPort = this.applicationContext.getEnvironment()
				.getProperty("local.server.port", Integer.class);
		Integer localManagementPort = this.applicationContext.getEnvironment()
				.getProperty("local.management.port", Integer.class);
		assertThat(localServerPort).isNotNull();
		assertThat(localManagementPort).isNotNull();
		assertThat(localServerPort).isEqualTo(localManagementPort);
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void portPropertiesOnDifferentPort() throws Exception {
		new ServerPortInfoApplicationContextInitializer()
				.initialize(this.applicationContext);
		this.applicationContext.register(RootConfig.class, DifferentPortConfig.class,
				BaseConfiguration.class, EndpointWebMvcAutoConfiguration.class,
				ErrorMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		Integer localServerPort = this.applicationContext.getEnvironment()
				.getProperty("local.server.port", Integer.class);
		Integer localManagementPort = this.applicationContext.getEnvironment()
				.getProperty("local.management.port", Integer.class);
		assertThat(localServerPort).isNotNull();
		assertThat(localManagementPort).isNotNull();
		assertThat(localServerPort).isNotEqualTo(localManagementPort);
		assertThat(this.applicationContext.getBean(ServerPortConfig.class).getCount())
				.isEqualTo(2);
		this.applicationContext.close();
		assertAllClosed();
	}

	@Test
	public void singleRequestMappingInfoHandlerMappingBean() throws Exception {
		this.applicationContext.register(RootConfig.class, BaseConfiguration.class,
				ServerPortConfig.class, EndpointWebMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		RequestMappingInfoHandlerMapping mapping = this.applicationContext
				.getBean(RequestMappingInfoHandlerMapping.class);
		assertThat(mapping).isNotEqualTo(instanceOf(EndpointHandlerMapping.class));
	}

	@Test
	public void endpointsDefaultConfiguration() throws Exception {
		this.applicationContext.register(RootConfig.class, BaseConfiguration.class,
				ServerPortConfig.class, EndpointWebMvcAutoConfiguration.class);
		this.applicationContext.refresh();
		// /health, /metrics, /env, /actuator (/shutdown is disabled by default)
		assertThat(this.applicationContext.getBeansOfType(MvcEndpoint.class)).hasSize(4);
	}

	@Test
	public void endpointsAllDisabled() throws Exception {
		this.applicationContext.register(RootConfig.class, BaseConfiguration.class,
				ServerPortConfig.class, EndpointWebMvcAutoConfiguration.class);
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"ENDPOINTS_ENABLED:false");
		this.applicationContext.refresh();
		assertThat(this.applicationContext.getBeansOfType(MvcEndpoint.class)).isEmpty();
	}

	@Test
	public void environmentEndpointDisabled() throws Exception {
		endpointDisabled("env", EnvironmentMvcEndpoint.class);
	}

	@Test
	public void environmentEndpointEnabledOverride() throws Exception {
		endpointEnabledOverride("env", EnvironmentMvcEndpoint.class);
	}

	@Test
	public void metricsEndpointDisabled() throws Exception {
		endpointDisabled("metrics", MetricsMvcEndpoint.class);
	}

	@Test
	public void metricsEndpointEnabledOverride() throws Exception {
		endpointEnabledOverride("metrics", MetricsMvcEndpoint.class);
	}

	@Test
	public void healthEndpointDisabled() throws Exception {
		endpointDisabled("health", HealthMvcEndpoint.class);
	}

	@Test
	public void healthEndpointEnabledOverride() throws Exception {
		endpointEnabledOverride("health", HealthMvcEndpoint.class);
	}

	@Test
	public void shutdownEndpointEnabled() {
		this.applicationContext.register(RootConfig.class, BaseConfiguration.class,
				ServerPortConfig.class, EndpointWebMvcAutoConfiguration.class);
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"endpoints.shutdown.enabled:true");
		this.applicationContext.refresh();
		assertThat(this.applicationContext.getBeansOfType(ShutdownMvcEndpoint.class))
				.hasSize(1);
	}

	@Test
	public void actuatorEndpointEnabledIndividually() {
		this.applicationContext.register(RootConfig.class, BaseConfiguration.class,
				ServerPortConfig.class, EndpointWebMvcAutoConfiguration.class);
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"endpoints.enabled:false", "endpoints.actuator.enabled:true");
		this.applicationContext.refresh();
		assertThat(this.applicationContext.getBeansOfType(HalJsonMvcEndpoint.class))
				.hasSize(1);
	}

	private void endpointDisabled(String name, Class<? extends MvcEndpoint> type) {
		this.applicationContext.register(RootConfig.class, BaseConfiguration.class,
				ServerPortConfig.class, EndpointWebMvcAutoConfiguration.class);
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				String.format("endpoints.%s.enabled:false", name));
		this.applicationContext.refresh();
		assertThat(this.applicationContext.getBeansOfType(type)).isEmpty();
	}

	private void endpointEnabledOverride(String name, Class<? extends MvcEndpoint> type)
			throws Exception {
		this.applicationContext.register(RootConfig.class, BaseConfiguration.class,
				ServerPortConfig.class, EndpointWebMvcAutoConfiguration.class);
		EnvironmentTestUtils.addEnvironment(this.applicationContext,
				"endpoints.enabled:false",
				String.format("endpoints_%s_enabled:true", name));
		this.applicationContext.refresh();
		assertThat(this.applicationContext.getBeansOfType(type)).hasSize(1);
	}

	private void assertAllClosed() throws Exception {
		assertContent("/controller", ports.get().server, null);
		assertContent("/endpoint", ports.get().server, null);
		assertContent("/controller", ports.get().management, null);
		assertContent("/endpoint", ports.get().management, null);
	}

	public void assertContent(String url, int port, Object expected) throws Exception {
		SimpleClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory();
		ClientHttpRequest request = clientHttpRequestFactory
				.createRequest(new URI("http://localhost:" + port + url), HttpMethod.GET);
		try {
			ClientHttpResponse response = request.execute();
			if (HttpStatus.NOT_FOUND.equals(response.getStatusCode())) {
				throw new FileNotFoundException();
			}
			try {
				String actual = StreamUtils.copyToString(response.getBody(),
						Charset.forName("UTF-8"));
				if (expected instanceof Matcher) {
					assertThat(actual).is(Matched.by((Matcher<?>) expected));
				}
				else {
					assertThat(actual).isEqualTo(expected);
				}
			}
			finally {
				response.close();
			}
		}
		catch (Exception ex) {
			if (expected == null) {
				if (SocketException.class.isInstance(ex)
						|| FileNotFoundException.class.isInstance(ex)) {
					return;
				}
			}
			throw ex;
		}
	}

	public boolean hasHeader(String url, int port, String header) throws Exception {
		SimpleClientHttpRequestFactory clientHttpRequestFactory = new SimpleClientHttpRequestFactory();
		ClientHttpRequest request = clientHttpRequestFactory
				.createRequest(new URI("http://localhost:" + port + url), HttpMethod.GET);
		ClientHttpResponse response = request.execute();
		return response.getHeaders().containsKey(header);
	}

	private static class Ports {

		int server = SocketUtils.findAvailableTcpPort();

		int management = SocketUtils.findAvailableTcpPort();

	}

	@Configuration
	@Import({ PropertyPlaceholderAutoConfiguration.class,
			EmbeddedServletContainerAutoConfiguration.class,
			JacksonAutoConfiguration.class, EndpointAutoConfiguration.class,
			HttpMessageConvertersAutoConfiguration.class,
			DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
			ManagementServerPropertiesAutoConfiguration.class,
			ServerPropertiesAutoConfiguration.class })
	protected static class BaseConfiguration {

	}

	@Configuration
	public static class RootConfig {

		@Bean
		public TestController testController() {
			return new TestController();
		}

	}

	@Configuration
	public static class EndpointConfig {

		@Bean
		public TestEndpoint testEndpoint() {
			return new TestEndpoint();
		}

	}

	@Configuration
	public static class ServerPortConfig {

		private int count = 0;

		public int getCount() {
			return this.count;
		}

		@Bean
		public ServerProperties serverProperties() {
			ServerProperties properties = new ServerProperties() {
				@Override
				public void customize(ConfigurableEmbeddedServletContainer container) {
					ServerPortConfig.this.count++;
					super.customize(container);
				}
			};
			properties.setPort(server.getPort());
			properties.setContextPath(server.getContextPath());
			return properties;
		}

	}

	@Controller
	public static class TestController {

		@RequestMapping("/controller")
		@ResponseBody
		public String requestMappedMethod() {
			return "controlleroutput";
		}

	}

	@Configuration
	public static class SpecificContainerConfig {

		@Bean
		public SpecificEmbeddedServletContainerFactory embeddedServletContainerFactory() {
			return new SpecificEmbeddedServletContainerFactory();
		}

	}

	@Configuration
	@Import(ServerPortConfig.class)
	public static class DifferentPortConfig {

		@Bean
		public ManagementServerProperties managementServerProperties() {
			return management;
		}

		@Bean
		public EndpointHandlerMappingCustomizer mappingCustomizer() {
			return new EndpointHandlerMappingCustomizer() {

				@Override
				public void customize(EndpointHandlerMapping mapping) {
					mapping.setInterceptors(new Object[] { interceptor() });
				}

			};
		}

		@Bean
		protected TestInterceptor interceptor() {
			return new TestInterceptor();
		}

		protected static class TestInterceptor extends HandlerInterceptorAdapter {

			private int count = 0;

			@Override
			public void postHandle(HttpServletRequest request,
					HttpServletResponse response, Object handler,
					ModelAndView modelAndView) throws Exception {
				this.count++;
			}

			public int getCount() {
				return this.count;
			}

		}

	}

	@Configuration
	@Import(ServerPortConfig.class)
	public static class RandomPortConfig {

		@Bean
		public ManagementServerProperties managementServerProperties() {
			ManagementServerProperties properties = new ManagementServerProperties();
			properties.setPort(0);
			return properties;
		}

	}

	@Configuration
	@Import(ServerPortConfig.class)
	public static class DisableConfig {

		@Bean
		public ManagementServerProperties managementServerProperties() {
			ManagementServerProperties properties = new ManagementServerProperties();
			properties.setPort(-1);
			return properties;
		}

	}

	public static class TestEndpoint implements MvcEndpoint {

		@RequestMapping
		@ResponseBody
		public String invoke() {
			return "endpointoutput";
		}

		@Override
		public String getPath() {
			return "/endpoint";
		}

		@Override
		public boolean isSensitive() {
			return true;
		}

		@Override
		@SuppressWarnings("rawtypes")
		public Class<? extends Endpoint> getEndpointType() {
			return Endpoint.class;
		}

	}

	private static class GrabManagementPort
			implements ApplicationListener<EmbeddedServletContainerInitializedEvent> {

		private ApplicationContext rootContext;

		private EmbeddedServletContainer servletContainer;

		GrabManagementPort(ApplicationContext rootContext) {
			this.rootContext = rootContext;
		}

		@Override
		public void onApplicationEvent(EmbeddedServletContainerInitializedEvent event) {
			if (event.getApplicationContext() != this.rootContext) {
				this.servletContainer = event.getEmbeddedServletContainer();
			}
		}

		public EmbeddedServletContainer getServletContainer() {
			return this.servletContainer;
		}

	}

	private static class SpecificEmbeddedServletContainerFactory
			extends TomcatEmbeddedServletContainerFactory {

	}

}
