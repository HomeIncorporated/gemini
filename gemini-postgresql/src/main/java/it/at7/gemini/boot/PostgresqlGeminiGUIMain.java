package it.at7.gemini.boot;

import it.at7.gemini.api.Api;
import it.at7.gemini.core.Gemini;
import it.at7.gemini.gui.Gui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

@EnableAutoConfiguration
public class PostgresqlGeminiGUIMain {
    private static final Logger logger = LoggerFactory.getLogger(PostgresqlGeminiGUIMain.class);

    public static void main(String[] args) {

        logger.info("***** STARTING GEMINI POSTRESQL MAIN *****");

        logger.info("STARTING - GEMINI-ROOT APP CONTEXT ");
        ConfigurableApplicationContext root = new SpringApplicationBuilder()
                .parent(Gemini.class, PostgresqlGeminiGUIMain.class).web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
        root.setId("GEMINI-ROOT");
        Gemini gemini = root.getBean(Gemini.class);
        gemini.init();
        logger.info("STARTED - GEMINI-ROOT APP CONTEXT");


        logger.info("STARTING - GEMINI-GUI APP CONTEXT ");
        ConfigurableApplicationContext gui = new SpringApplicationBuilder()
                .parent(root).sources(Api.class, Gui.class, PostgresqlGeminiGUIMain.class).web(WebApplicationType.SERVLET)
                .bannerMode(Banner.Mode.OFF)
                .run(args);
        gui.setId("GEMINI-GUI");
        logger.info("STARTED - GEMINI-GUI APP CONTEXT");


    }
}
