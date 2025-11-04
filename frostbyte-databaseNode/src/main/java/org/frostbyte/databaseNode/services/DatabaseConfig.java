package org.frostbyte.databaseNode.services;

import org.frostbyte.databaseNode.models.configModel;
import org.frostbyte.databaseNode.models.configModel.DatabaseDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.JpaTransactionManager;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.HashMap;
import java.util.Map;

@Configuration
@DependsOn("configModel")
public class DatabaseConfig {

    private final configModel config;

    @Autowired
    public DatabaseConfig(@Qualifier("configModel") configModel config) {
        this.config = config;
    }


    @Bean
    public DataSource dataSource() {
        DatabaseDetails db = config.getDatabase();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");

        // Build JDBC URL: jdbc:postgresql://host:port/database
        String url = String.format("jdbc:postgresql://%s:%d/%s", db.getHost(), db.getPort(), db.getName());
        dataSource.setUrl(url);
        dataSource.setUsername(db.getUsername());
        dataSource.setPassword(db.getPassword());

        return dataSource;
    }

    // Required for JPA
    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan("org.frostbyte.databaseNode.entities");  // <-- Your entity package
        emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());

        Map<String, Object> jpaProps = new HashMap<>();
        jpaProps.put("hibernate.hbm2ddl.auto", "update"); // Can be 'validate', 'create', 'none'
        jpaProps.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        jpaProps.put("hibernate.show_sql", "true");

        emf.setJpaPropertyMap(jpaProps);

        return emf;
    }

    @Bean
    public JpaTransactionManager transactionManager(LocalContainerEntityManagerFactoryBean emf) {
        JpaTransactionManager txManager = new JpaTransactionManager();
        txManager.setEntityManagerFactory(emf.getObject());
        return txManager;
    }
}
