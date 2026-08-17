package org.recap.camel.activemq;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.camel.component.activemq.ActiveMQComponent;
import org.apache.camel.CamelContext;
import org.recap.PropertyKeyConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;


/**
 * Created by premkb on 12/10/16.
 */

@Component
public class ActivemqRegistrar {

    /**
     * This class is initialized to register the activemq component to the camel context with the default message broker url.
     *
     * @param camelContext
     * @param defaultBrokerURL
     * @throws JMSException
     */
    @Autowired
    public ActivemqRegistrar(CamelContext camelContext , @Value("${" + PropertyKeyConstants.ACTIVEMQ_BROKER_URL + "}") String defaultBrokerURL) {
        ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(defaultBrokerURL);
        ActiveMQComponent activeMQComponent = new ActiveMQComponent();
        activeMQComponent.setConnectionFactory(connectionFactory);
        activeMQComponent.setTrustAllPackages(true);
        camelContext.addComponent("scsbactivemq", activeMQComponent);
    }
}
