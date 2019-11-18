package io.smallrye.reactive.messaging.jms;

import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.literal.NamedLiteral;
import javax.inject.Inject;
import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.eclipse.microprofile.reactive.messaging.spi.IncomingConnectorFactory;
import org.eclipse.microprofile.reactive.messaging.spi.OutgoingConnectorFactory;
import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.operators.SubscriberBuilder;

@ApplicationScoped
@Connector(JmsConnector.CONNECTOR_NAME)
public class JmsConnector implements IncomingConnectorFactory, OutgoingConnectorFactory {

    static final String CONNECTOR_NAME = "smallrye-jms";

    @Inject
    Instance<ConnectionFactory> factories;

    @Inject
    Instance<Jsonb> jsonb;

    @Inject
    @ConfigProperty(name = "smallrye.jms.threads.max-pool-size", defaultValue = "10")
    int maxPoolSize;

    @Inject
    @ConfigProperty(name = "smallrye.jms.threads.ttl", defaultValue = "60")
    int ttl;

    private ExecutorService executor;
    private Jsonb json;

    @PostConstruct
    public void init() {
        this.executor = new ThreadPoolExecutor(0, maxPoolSize, ttl, TimeUnit.SECONDS, new SynchronousQueue<>());
        if (jsonb.isUnsatisfied()) {
            this.json = JsonbBuilder.create();
        } else {
            this.json = jsonb.get();
        }

    }

    @PreDestroy
    public void cleanup() {
        this.executor.shutdown();
    }

    @Override
    public PublisherBuilder<? extends Message<?>> getPublisherBuilder(Config config) {
        JMSContext context = createJmsContext(config);
        return new JmsSource(context, config, json, executor).getSource();
    }

    private JMSContext createJmsContext(Config config) {
        String factoryName = config.getOptionalValue("connection-factory-name", String.class).orElse(null);
        ConnectionFactory factory = pickTheFactory(factoryName);
        return createContext(factory,
                config.getOptionalValue("username", String.class).orElse(null),
                config.getOptionalValue("password", String.class).orElse(null),
                config.getOptionalValue("session-mode", String.class).orElse("AUTO_ACKNOWLEDGE"));
    }

    @Override
    public SubscriberBuilder<? extends Message<?>, Void> getSubscriberBuilder(Config config) {
        JMSContext context = createJmsContext(config);
        return new JmsSink(context, config, json, executor).getSink();
    }

    private ConnectionFactory pickTheFactory(String factoryName) {
        if (factories.isUnsatisfied()) {
            throw new IllegalStateException("Cannot find a javax.jms.ConnectionFactory bean");
        }

        Iterator<ConnectionFactory> iterator;
        if (factoryName == null) {
            iterator = factories.iterator();
        } else {
            iterator = factories.select(NamedLiteral.of(factoryName)).iterator();
        }

        if (!iterator.hasNext()) {
            if (factoryName == null) {
                throw new IllegalStateException("Cannot find a javax.jms.ConnectionFactory bean");
            } else {
                throw new IllegalStateException("Cannot find a javax.jms.ConnectionFactory bean named " + factoryName);
            }
        }

        return iterator.next();
    }

    private JMSContext createContext(ConnectionFactory factory, String username, String password, String mode) {
        int sessionMode;
        switch (mode.toUpperCase()) {
            case "AUTO_ACKNOWLEDGE":
                sessionMode = JMSContext.AUTO_ACKNOWLEDGE;
                break;
            case "SESSION_TRANSACTED":
                sessionMode = JMSContext.SESSION_TRANSACTED;
                break;
            case "CLIENT_ACKNOWLEDGE":
                sessionMode = JMSContext.CLIENT_ACKNOWLEDGE;
                break;
            case "DUPS_OK_ACKNOWLEDGE":
                sessionMode = JMSContext.DUPS_OK_ACKNOWLEDGE;
                break;
            default:
                throw new IllegalArgumentException("Unknown session mode: " + mode);
        }

        if (username != null) {
            return factory.createContext(username, password, sessionMode);
        } else {
            return factory.createContext(sessionMode);
        }
    }
}
