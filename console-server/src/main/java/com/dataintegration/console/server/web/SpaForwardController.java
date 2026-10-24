package com.dataintegration.console.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the bundled Vue app for its history-mode routes, so a browser refresh on e.g. /jobs/12
 * does not 404. Keep in sync with console-ui/src/router.
 */
@Controller
public class SpaForwardController {

    @GetMapping({
        "/login",
        "/projects/**",
        "/datasources/**",
        "/jobs/**",
        "/instances/**",
        "/cluster/**"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
