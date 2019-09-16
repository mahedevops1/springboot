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

package org.springframework.boot.autoconfigure.liquibase;

import java.util.function.Supplier;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;

import liquibase.change.DatabaseChange;
import liquibase.integration.spring.SpringLiquibase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.jdbc.JdbcOperationsDependsOnPostProcessor;
import org.springframework.boot.autoconfigure.jdbc.NamedParameterJdbcOperationsDependsOnPostProcessor;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration.LiquibaseDataSourceCondition;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.orm.jpa.AbstractEntityManagerFactoryBean;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * {@link EnableAutoConfiguration Auto-configuration} for Liquibase.
 *
 * @author Marcel Overdijk
 * @author Dave Syer
 * @author Phillip Webb
 * @author Eddú Meléndez
 * @author Andy Wilkinson
 * @author Dominic Gunn
 * @author Dan Zheng
 * @author András Deák
 * @author Tristan Deloche
 * @since 1.1.0
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ SpringLiquibase.class, DatabaseChange.class })
@ConditionalOnProperty(prefix = "spring.liquibase", name = "enabled", matchIfMissing = true)
@Conditional(LiquibaseDataSourceCondition.class)
@AutoConfigureAfter({ DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class })
public class LiquibaseAutoConfiguration {

	@Bean
	public LiquibaseSchemaManagementProvider liquibaseDefaultDdlModeProvider(
			ObjectProvider<SpringLiquibase> liquibases) {
		return new LiquibaseSchemaManagementProvider(liquibases);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnMissingBean(SpringLiquibase.class)
	@EnableConfigurationProperties({ DataSourceProperties.class, LiquibaseProperties.class })
	@Import(LiquibaseJpaDependencyConfiguration.class)
	public static class LiquibaseConfiguration {

		private final LiquibaseProperties properties;

		private final ConfigurableListableBeanFactory beanFactory;

		public LiquibaseConfiguration(LiquibaseProperties properties, ConfigurableListableBeanFactory beanFactory) {
			this.properties = properties;
			this.beanFactory = beanFactory;
		}

		@Bean
		public SpringLiquibase liquibase(DataSourceProperties dataSourceProperties,
				ObjectProvider<DataSource> dataSource,
				@LiquibaseDataSource ObjectProvider<DataSource> liquibaseDataSource) {
			SpringLiquibase liquibase = createSpringLiquibase(liquibaseDataSource.getIfAvailable(),
					dataSource.getIfUnique(), dataSourceProperties);
			liquibase.setChangeLog(this.properties.getChangeLog());
			liquibase.setContexts(this.properties.getContexts());
			liquibase.setDefaultSchema(this.properties.getDefaultSchema());
			liquibase.setLiquibaseSchema(this.properties.getLiquibaseSchema());
			liquibase.setLiquibaseTablespace(this.properties.getLiquibaseTablespace());
			liquibase.setDatabaseChangeLogTable(this.properties.getDatabaseChangeLogTable());
			liquibase.setDatabaseChangeLogLockTable(this.properties.getDatabaseChangeLogLockTable());
			liquibase.setDropFirst(this.properties.isDropFirst());
			liquibase.setShouldRun(this.properties.isEnabled());
			liquibase.setLabels(this.properties.getLabels());
			liquibase.setChangeLogParameters(this.properties.getParameters());
			liquibase.setRollbackFile(this.properties.getRollbackFile());
			liquibase.setTestRollbackOnUpdate(this.properties.isTestRollbackOnUpdate());
			return liquibase;
		}

		private SpringLiquibase createSpringLiquibase(DataSource liquibaseAnnotatedDatasource, DataSource dataSource,
				DataSourceProperties dataSourceProperties) {
			DataSource liquibaseDataSource = getDataSource(liquibaseAnnotatedDatasource, dataSource);
			boolean closeDataSourceOnceMigrated = false;

			if (liquibaseDataSource == null) {
				liquibaseDataSource = createNewDataSource(dataSourceProperties);
				closeDataSourceOnceMigrated = true;
			}

			if (liquibaseAnnotatedDatasource != null) {
				closeDataSourceOnceMigrated = findLiquibaseDataSourceAnnotation().closeDataSourceOnceMigrated();
			}

			SpringLiquibase liquibase = closeDataSourceOnceMigrated ? new DataSourceClosingSpringLiquibase()
					: new SpringLiquibase();
			liquibase.setDataSource(liquibaseDataSource);
			return liquibase;
		}

		private DataSource getDataSource(DataSource liquibaseDataSource, DataSource dataSource) {
			if (liquibaseDataSource != null) {
				return liquibaseDataSource;
			}
			if (this.properties.getUrl() == null && this.properties.getUser() == null) {
				return dataSource;
			}
			return null;
		}

		private DataSource createNewDataSource(DataSourceProperties dataSourceProperties) {
			String url = getProperty(this.properties::getUrl, dataSourceProperties::determineUrl);
			String user = getProperty(this.properties::getUser, dataSourceProperties::determineUsername);
			String password = getProperty(this.properties::getPassword, dataSourceProperties::determinePassword);
			return DataSourceBuilder.create().url(url).username(user).password(password).build();
		}

		private String getProperty(Supplier<String> property, Supplier<String> defaultValue) {
			String value = property.get();
			return (value != null) ? value : defaultValue.get();
		}

		/**
		 * Finds the {@link LiquibaseDataSource} annotation on the single
		 * liquibase-specific {@link DataSource} bean declaration.
		 * @return the {@link LiquibaseDataSource} annotation on the bean declaration
		 * @throws IllegalStateException if there such a bean declaration isn't present
		 */
		private LiquibaseDataSource findLiquibaseDataSourceAnnotation() {
			String[] beanNames = this.beanFactory.getBeanNamesForAnnotation(LiquibaseDataSource.class);
			String beanName = beanNames[0]; // assume only one such bean
			BeanDefinition beanDefinition = this.beanFactory.getBeanDefinition(beanName);
			AnnotatedTypeMetadata beanDefinitionSource = (AnnotatedTypeMetadata) beanDefinition.getSource();
			MergedAnnotations beanDeclarationAnnotations = beanDefinitionSource.getAnnotations();
			return beanDeclarationAnnotations.get(LiquibaseDataSource.class).synthesize();
		}

	}

	/**
	 * Additional configuration to ensure that {@link EntityManagerFactory} beans depend
	 * on the liquibase bean.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(LocalContainerEntityManagerFactoryBean.class)
	@ConditionalOnBean(AbstractEntityManagerFactoryBean.class)
	protected static class LiquibaseJpaDependencyConfiguration extends EntityManagerFactoryDependsOnPostProcessor {

		public LiquibaseJpaDependencyConfiguration() {
			super(SpringLiquibase.class);
		}

	}

	/**
	 * Additional configuration to ensure that {@link JdbcOperations} beans depend on the
	 * liquibase bean.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(JdbcOperations.class)
	@ConditionalOnBean(JdbcOperations.class)
	protected static class LiquibaseJdbcOperationsDependencyConfiguration extends JdbcOperationsDependsOnPostProcessor {

		public LiquibaseJdbcOperationsDependencyConfiguration() {
			super(SpringLiquibase.class);
		}

	}

	/**
	 * Additional configuration to ensure that {@link NamedParameterJdbcOperations} beans
	 * depend on the liquibase bean.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(NamedParameterJdbcOperations.class)
	@ConditionalOnBean(NamedParameterJdbcOperations.class)
	protected static class LiquibaseNamedParameterJdbcOperationsDependencyConfiguration
			extends NamedParameterJdbcOperationsDependsOnPostProcessor {

		public LiquibaseNamedParameterJdbcOperationsDependencyConfiguration() {
			super(SpringLiquibase.class);
		}

	}

	static final class LiquibaseDataSourceCondition extends AnyNestedCondition {

		LiquibaseDataSourceCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@ConditionalOnBean(DataSource.class)
		private static final class DataSourceBeanCondition {

		}

		@ConditionalOnProperty(prefix = "spring.liquibase", name = "url", matchIfMissing = false)
		private static final class LiquibaseUrlCondition {

		}

	}

}
