package it.at7.gemini.core;

import it.at7.gemini.conf.State;
import it.at7.gemini.core.events.EventManagerInit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ComponentScan({"it.at7.gemini.core"})
@Service
public class Gemini {
    private final static Logger logger = LoggerFactory.getLogger(Gemini.class);

    private final ApplicationContext context;
    private final StateManagerImpl stateManager;
    private final SchemaManagerInit schemaManagerInit;
    private final EventManagerInit eventManagerInit;
    private final TransactionManager transactionManager;

    private List<Module> modules;

    @Autowired
    public Gemini(StateManagerImpl stateManager, SchemaManagerInit schemaManagerInit, ApplicationContext applicationContext, EventManagerInit eventManagerInit, TransactionManager transactionManager) {
        this.stateManager = stateManager;
        this.schemaManagerInit = schemaManagerInit;
        this.context = applicationContext;
        this.eventManagerInit = eventManagerInit;
        this.transactionManager = transactionManager;
    }

    public void init() {
        try {
            loadPredefinedBeans();
            loadModules();
            start();
            try (Transaction transaction = transactionManager.openTransaction()) {
                // schemas are initialized in a single transaction
                initializeSchemaAndEvents(transaction);
                transaction.commit();
            }
            initialize();
        } catch (Exception e) {
            logger.error("Error During start of Gemini", e);
            System.exit(1);
        }
    }

    private void loadPredefinedBeans() {
        context.getBean(Services.class);
    }

    private void loadModules() {
        logger.info("MODULES LOADING");
        Map<String, Module> modulesMap = context.getBeansOfType(Module.class);
        for (Module module : modulesMap.values()) {
            logger.info("Found module {} withGeminiSearchString dependecies {}", module.getName(), module.getDependencies());
            stateManager.register(module);
        }
        this.modules = modulesMap.values().stream()
                .sorted(Comparator.comparingInt(Module::order))
                .collect(Collectors.toList());
    }

    private void start() {
        stateManager.changeState(State.STARTED);
    }


    private void initializeSchemaAndEvents(Transaction transaction) throws Exception {
        logger.info("SCHEMAS/EVENTS INITIALIZATION");
        schemaManagerInit.initializeSchemasStorage(modules, transaction);
        eventManagerInit.loadEvents(modules);
        stateManager.changeState(State.SCHEMA_EVENTS_LOADED);
        schemaManagerInit.initializeSchemaEntityRecords(modules, transaction);
    }

    private void initialize() {
        stateManager.changeState(State.INITIALIZED);
    }

}
