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
package de.bushnaq.abdalla.youtube.service;

import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Opens the application URL in a Chrome app-mode window once Spring Boot has finished starting.
 *
 * <p>Uses Selenium {@link ChromeDriver} with the {@code --app} flag so the browser window has
 * no toolbar and looks like a native desktop application.  Selenium Manager (built into Selenium
 * 4.6+) automatically locates the installed Chrome binary and downloads a matching
 * {@code chromedriver} executable — no manual path configuration is required.
 *
 * <p>The {@link WebDriver} instance is kept alive for the lifetime of the Spring application
 * context and is closed cleanly on shutdown via {@link #onShutdown()}.
 */
@Component
@Slf4j
public class BrowserLauncherService {

    /**
     * Height of the browser app window in pixels.
     */
    private static final int       WINDOW_HEIGHT = 600;
    /**
     * Width of the browser app window in pixels.
     */
    private static final int       WINDOW_WIDTH  = 800;
    /**
     * The running Chrome window; {@code null} if the browser could not be opened.
     */
    private              WebDriver driver;
    /**
     * The HTTP port on which the embedded Tomcat is listening.
     */
    @Value("${server.port:8080}")
    private              int       port;
    /** Desired window size; set before {@link #getDriver()} is called. */
    private              Dimension windowSize = new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT);

    @PreDestroy
    public void destroy() {
        if (driver != null) {
            driver.quit();//quit the driver and close all windows
            driver = null;
        }
        log.trace("quit selenium driver");
    }

    public WebDriver getDriver() {
        if (driver != null) {
            return driver;
        }

        WebDriverManager.chromedriver().setup();

        ChromeOptions options = new ChromeOptions();

        // Remove the "Chrome is being controlled by automated test software" banner
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);

        // Check if we're running in headless mode (for CI environment)
        boolean headlessMode = isSeleniumHeadless();
        if (headlessMode) {
            log.info("creating selenium driver, Running Chrome in headless mode");
            options.addArguments("--headless=new");
            options.addArguments("--disable-gpu");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--disable-extensions");
            options.addArguments("--disable-browser-side-navigation");
            options.addArguments("--disable-web-security");
            options.addArguments("--dns-prefetch-disable");
            // Add a longer timeout for the page load
            options.setPageLoadTimeout(Duration.ofSeconds(60));
            // Disable the "Save password?" prompt and grant clipboard permissions
            options.setExperimentalOption("prefs", Map.of(
                    "credentials_enable_service", false,
                    "profile.password_manager_enabled", false,
                    "profile.content_settings.exceptions.clipboard", Map.of(
                            "*", Map.of("setting", 1)
                    )
            ));
        } else {
            log.info("creating selenium driver");
            // Disable the "Save password?" prompt and grant clipboard permissions
            options.setExperimentalOption("prefs", Map.of(
                    "credentials_enable_service", false,
                    "profile.password_manager_enabled", false,
                    "profile.content_settings.exceptions.clipboard", Map.of(
                            "*", Map.of("setting", 1)
                    )
            ));
        }

        options.addArguments("--remote-allow-origins=*");
        // Grant clipboard permissions without prompting
        options.addArguments("--disable-features=ClipboardContentSetting");
        options.setCapability(CapabilityType.ACCEPT_INSECURE_CERTS, true);

        // Set browser locale if specified via system property (e.g., -Dtest.locale=de-DE)
        String testLocale = System.getProperty("test.locale");
        if (testLocale != null && !testLocale.isEmpty()) {
            log.info("Setting browser locale to: " + testLocale);
            options.addArguments("--lang=" + testLocale);
        }

        // Enable browser console logging to capture JavaScript console.log messages
        // Use W3C-compliant logging preferences for modern Chrome/Selenium
        options.setCapability("goog:loggingPrefs", Map.of(
                "browser", "ALL",
                "driver", "ALL",
                "performance", "ALL"
        ));


        // Set a higher script timeout to prevent connection issues
        driver = new ChromeDriver(options);
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));

        // Set default window size if not in headless mode
        if (!headlessMode) {
            if (windowSize != null) {
                getDriver().manage().window().setSize(windowSize);
                getDriver().manage().window().setPosition(new Point(33, 22));
            } else {
                //maximize window by default
                getDriver().manage().window().maximize();
            }
        }
        return driver;
    }

    public static boolean isSeleniumHeadless() {
        return false;
    }

    /**
     * Fires once Spring Boot is fully started.  Opens the application URL in a Chrome
     * app-mode window sized to {@value #WINDOW_WIDTH}×{@value #WINDOW_HEIGHT} pixels.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String url = "http://localhost:" + port;
        log.info("Opening browser at {}", url);
        try {
            getDriver();
            driver.get(url);
            log.info("Chrome window opened at {}x{}", WINDOW_WIDTH, WINDOW_HEIGHT);
        } catch (Exception ex) {
            log.warn("Could not open Chrome via Selenium — is Chrome installed? ({})", ex.getMessage(), ex);
        }
    }


}
