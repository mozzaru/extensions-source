import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Ikiru"
    pkgName = "id.mangatale"
    // natsuid contributed baseVersionCode 6, so the previous release was 1.6.56.
    versionCode = 57
    contentWarning = ContentWarning.SAFE
    libVersion = "1.6"

    source {
        lang = "id"
        // Site moved from the WordPress (natsuid theme) install at 08.ikiru.wtf
        // to a Nuxt SPA on 09.ikiru.wtf with a JSON API.
        baseUrl {
            custom("https://09.ikiru.wtf")
        }
        // Formerly "MangaTale"
        id = 1532456597012176985L
    }

    deeplink {
        path("/manga/..*")
    }
}
