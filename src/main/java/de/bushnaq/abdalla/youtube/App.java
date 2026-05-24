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

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Spring Boot entry point for the youtube-sync Vaadin application.
 *
 * <p>Start the application with {@code java -jar youtube-sync.jar}; the embedded Tomcat
 * server starts on port 8080 and the default browser opens automatically.
 *
 * <p>The two {@code @StyleSheet} annotations load the Lumo design-system CSS directly.
 * This is the Vaadin 25 recommended approach — the deprecated {@code @Theme} annotation
 * on {@code AppShellConfigurator} is no longer needed.
 */
@SpringBootApplication
@ComponentScan
@StyleSheet("context://" + Lumo.STYLESHEET)
@StyleSheet("context://" + Lumo.UTILITY_STYLESHEET)
public class App implements AppShellConfigurator {

    /**
     * Application entry point.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
