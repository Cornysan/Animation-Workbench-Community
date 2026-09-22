package com.playmation.motionlabsbackend.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.AbstractResourceResolver
import org.springframework.web.servlet.resource.ResourceResolverChain
import java.time.Duration

/**
 * Skripte, Stile, Schrift und Mannequin unter einer Adresse, die sich mit
 * jedem Build aendert - und deshalb fuer immer im Browser liegen bleiben darf.
 *
 * Vorher lief alles ueber Springs Standard-Handler, und Spring Security
 * haengt an jede Antwort ohne eigenen Cache-Header `no-cache, no-store`. Der
 * Browser holte also bei JEDEM Seitenwechsel three.js (185 KB gzip), das
 * Mannequin (334 KB, unkomprimiert) und die Schrift neu - gemessen am
 * 2026-09-23 per `curl -I` gegen das Portal.
 *
 * Die Version steht als PFADSTUECK vorn (`/assets/v/<commit>/stage.js`),
 * nicht als `?v=` hinten. Nur so erben die relativen ES-Importe sie: ein
 * `import './stage.js'` aus `/assets/v/1a2b3c4/pages/clip.js` landet von
 * selbst wieder unter derselben Version. Ein Query-Parameter ginge beim
 * ersten relativen Import verloren.
 *
 * Unter `/assets/v/<commit>/` liegt ausserdem `models/` - das Mannequin wird
 * aus den Modulen relativ zu `import.meta.url` geholt und bekommt so dieselbe
 * Version, ohne dass ein Skript den Commit kennen muss.
 *
 * Die alten Adressen (`/assets/app.js`, `/models/...`) bleiben erreichbar und
 * bleiben `no-cache`: was noch darauf zeigt, bekommt immer den frischen Stand.
 */
@Configuration
class StaticAssets(private val build: BuildStamp) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Lokal (`dev`) aendern sich die Dateien unter derselben Version - dort
        // darf nichts liegen bleiben, sonst sieht man seine eigene Aenderung nicht.
        val local = build.commit == "dev"

        registry.addResourceHandler("$PREFIX/*/**")
            .addResourceLocations("classpath:/static/assets/", "classpath:/static/")
            .setCacheControl(
                if (local) CacheControl.noCache()
                else CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()
            )
            .resourceChain(!local)
            .addResolver(DropVersionSegment())

        // Das Favicon fragt jeder Browser auf jeder Seite ab, und es aendert
        // sich so gut wie nie. Ein Tag ist genug, um nicht bei jedem Klick 35 KB
        // zu holen, und kurz genug, dass ein neues Zeichen ankommt.
        registry.addResourceHandler("/favicon.ico")
            .addResourceLocations("classpath:/static/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
    }

    /**
     * Wirft das Versionsstueck weg und sucht den Rest wie gewohnt.
     *
     * Jede Version wird angenommen, nicht nur die laufende. Eine Seite, die
     * waehrend des Ausrollens offen stand, laedt ihre spaeten Module
     * (`card-stage.js` erst, wenn eine Karte ins Bild scrollt) noch mit der
     * alten Version nach - ein 404 an dieser Stelle waere eine leere Karte, der
     * neue Stand ist das kleinere Uebel.
     */
    private class DropVersionSegment : AbstractResourceResolver() {
        override fun resolveResourceInternal(
            request: HttpServletRequest?,
            requestPath: String,
            locations: List<Resource>,
            chain: ResourceResolverChain,
        ): Resource? {
            val slash = requestPath.indexOf('/')
            if (slash <= 0) return null
            return chain.resolveResource(request, requestPath.substring(slash + 1), locations)
        }

        override fun resolveUrlPathInternal(
            resourceUrlPath: String,
            locations: List<Resource>,
            chain: ResourceResolverChain,
        ): String? = chain.resolveUrlPath(resourceUrlPath, locations)
    }

    companion object {
        const val PREFIX = "/assets/v"
    }
}
