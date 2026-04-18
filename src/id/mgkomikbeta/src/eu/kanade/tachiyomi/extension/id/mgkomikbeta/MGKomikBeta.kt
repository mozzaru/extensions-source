package eu.kanade.tachiyomi.extension.id.mgkomikbeta

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import java.text.SimpleDateFormat
import java.util.Locale

class MGKomikBeta :
    MangaThemesia(
        "MG Komik Beta",
        "https://web.mgkomik.cc",
        "id",
        mangaUrlDirectory = "/komik",
        dateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id")),
    )
