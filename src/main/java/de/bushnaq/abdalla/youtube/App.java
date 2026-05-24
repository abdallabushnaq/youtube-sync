/*
 * Copyright (C) 2025-2026 Abdalla Bushnaq
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
package de.bushnaq.abdalla.youtube;

import com.formdev.flatlaf.FlatDarkLaf;
import de.bushnaq.abdalla.youtube.ui.MainFrame;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import javax.swing.SwingUtilities;

/**
 * Spring Boot entry point for the youtube-sync Swing application.
 *
 * <p>Starts a non-web Spring application context (DI only — no embedded server),
 * installs the FlatLaf dark look-and-feel, then opens {@link MainFrame} on the
 * Swing Event Dispatch Thread.
 */
@SpringBootApplication
public class App {

    /**
     * Application entry point.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        // FlatLaf must be installed before any Swing component is created.
        FlatDarkLaf.setup();

        SpringApplication application = new SpringApplication(App.class);
        // AWT/Swing requires headless=false.
        application.setHeadless(false);
        ConfigurableApplicationContext ctx = application.run(args);

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = ctx.getBean(MainFrame.class);
            frame.setVisible(true);
        });
    }
}
