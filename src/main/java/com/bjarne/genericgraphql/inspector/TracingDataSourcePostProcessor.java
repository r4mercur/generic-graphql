package com.bjarne.genericgraphql.inspector;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Legt die {@link TracingDataSource} um die DataSource der Anwendung. Ohne laufenden Mitschnitt
 * reicht sie die Statements des Pools unveraendert durch.
 */
@Component
@ConditionalOnProperty(name = "inspector.enabled", havingValue = "true", matchIfMissing = true)
public class TracingDataSourcePostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof TracingDataSource)) {
            return new TracingDataSource(dataSource);
        }
        return bean;
    }
}
