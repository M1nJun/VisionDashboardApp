package com.visiondash.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * Serves the built SPA and hands every unknown path back to it.
 *
 * The dashboard uses real URLs (/dashboard/vision/C-2/EXAMPLE_C), so opening one directly or
 * reloading the page asks the server for a file that does not exist. Without this the
 * operator gets a 404 on refresh - which on a wall display means a blank screen nobody
 * knows how to recover.
 *
 * /api/** is untouched: an unknown API path must still 404 rather than quietly return
 * an HTML page, which is far harder to diagnose from a failing fetch.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Asset filenames carry a content hash, so a given URL's bytes never change and
        // the browser can hold them forever. A new build produces new filenames.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        // index.html names those hashed files, so it is the one thing that must never be
        // cached. Served with only a Last-Modified it fell into the browser's heuristic
        // cache, and a dashboard left open across a deployment kept running the previous
        // build against the new server - old columns, old panels, fields the API no
        // longer sends. no-store makes a reload always pick up the deployed build.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noStore())
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }
                        return new ClassPathResource("/static/index.html");
                    }
                });
    }
}
