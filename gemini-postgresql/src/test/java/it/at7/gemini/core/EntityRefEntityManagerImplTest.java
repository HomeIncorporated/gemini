package it.at7.gemini.core;

import it.at7.gemini.boot.IntegrationTestMain;
import it.at7.gemini.core.entitymanager.EntityRefEntityManagerAbstTest;
import it.at7.gemini.exceptions.GeminiException;
import org.springframework.context.ConfigurableApplicationContext;

public class EntityRefEntityManagerImplTest extends EntityRefEntityManagerAbstTest {

    @Override
    protected ConfigurableApplicationContext getApplicationContext() throws GeminiException {
        ConfigurableApplicationContext context = IntegrationTestMain.initializeGemini(IntegrationTestModule.class);
        insertDomainRecords();
        return context;
    }

}
