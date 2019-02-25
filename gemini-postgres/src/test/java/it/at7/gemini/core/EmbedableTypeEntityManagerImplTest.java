package it.at7.gemini.core;

import it.at7.gemini.boot.IntegrationTestMain;
import it.at7.gemini.core.entitymanager.EmbedableTypeEntityManagerAbsTest;
import it.at7.gemini.core.entitymanager.EntityRefEntityManagerAbstTest;
import it.at7.gemini.exceptions.GeminiException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.SQLException;

public class EmbedableTypeEntityManagerImplTest extends EmbedableTypeEntityManagerAbsTest {
    static ConfigurableApplicationContext contex;

    @BeforeClass
    public static void initializeTest() {
        contex = IntegrationTestMain.initializeGemini(IntegrationTestModule.class);
    }

    @AfterClass
    public static void after() {
        contex.close();
    }
}
