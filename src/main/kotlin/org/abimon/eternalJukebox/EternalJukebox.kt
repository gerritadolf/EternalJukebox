package org.abimon.eternalJukebox

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JSR310Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule
import com.mashape.unirest.http.JsonNode
import com.mashape.unirest.http.Unirest
import com.mashape.unirest.http.exceptions.UnirestException
import com.mashape.unirest.request.HttpRequestWithBody
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.net.PemKeyCertOptions
import io.vertx.ext.auth.oauth2.AccessToken
import io.vertx.ext.auth.oauth2.OAuth2Auth
import io.vertx.ext.auth.oauth2.OAuth2ClientOptions
import io.vertx.ext.auth.oauth2.OAuth2FlowType
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.*
import io.vertx.ext.web.sstore.LocalSessionStore
import org.abimon.eternalJukebox.objects.*
import org.abimon.notifly.NotificationPayload
import org.abimon.notifly.notification
import org.abimon.visi.collections.PoolableObject
import org.abimon.visi.io.errPrintln
import org.abimon.visi.io.forceError
import org.abimon.visi.lang.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.*

val eternalDir = File("eternal")
val songsDir = File("songs")
val audioDir = File("audio")
val logDir = File("logs")
val tmpUploadDir = File("uploads")

val configFile = File("config.json")
val projectHosting = "https://github.com/UnderMybrella/EternalJukebox" //Just in case this changes or needs to be referenced

val b64Encoder = Base64.getUrlEncoder()
val b64Decoder = Base64.getUrlEncoder()
val b64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
val b64CustomID = "CSTM[$b64Alphabet]+".toRegex()

var config = JukeboxConfig()

fun getIP(): String {
    try {
        Unirest.get("https://google.com").asString().body
    } catch(e: UnirestException) {
        println("The internet's offline. Or something *really* bad has happened: $e")
        return "localhost"
    }

    for (web in arrayOf("http://ipecho.net/plain", "http://icanhazip.com", "http://ipinfo.io/ip",
            "https://ident.me/", "http://checkip.amazonaws.com", "http://smart-ip.net/myip",
            "http://bot.whatismyipaddress.com", "https://secure.informaction.com/ipecho/", "http://curlmyip.com")) {
        try {
            return Unirest.get(web).asString().body
        } catch(e: UnirestException) {
            println("$web is offline, trying the next one...")
        }
    }

    return "localhost"
}

fun uploadGist(desc: String, content: String, name: String = "error.txt"): Optional<String> {
    val json = JSONObject()
    json.put("description", desc)
    json.put("public", true)

    val files = JSONObject()
    val file = JSONObject()
    file.put("content", content)
    files.put(name, file)
    json.put("files", files)

    val response = Unirest.post("https://api.github.com/gists")
            .header("Content-Type", "application/json")
            .header("Accept", "application/vnd.github.v3+json")
            .body(json.toString()).asString()

    when (response.status) {
        201 -> return (JSONObject(response.body)["html_url"] as String).asOptional()
        else -> {
            println("Gist failed with error ${response.status}: ${response.body}")
            return Optional.empty()
        }
    }
}

val objMapper: ObjectMapper = ObjectMapper()
        .registerModules(Jdk8Module(), KotlinModule(), JSR310Module(), JavaTimeModule(), ParameterNamesModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
        .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)

fun main(args: Array<String>) {
    Unirest.setObjectMapper(object : com.mashape.unirest.http.ObjectMapper {
        override fun writeValue(value: Any): String = objMapper.writeValueAsString(value)
        override fun <T : Any?> readValue(value: String, valueType: Class<T>): T = objMapper.readValue(value, valueType)
    })

    if (!eternalDir.exists())
        eternalDir.mkdir()
    if (!songsDir.exists())
        songsDir.mkdir()
    if (!audioDir.exists())
        audioDir.mkdir()
    if (!logDir.exists())
        logDir.mkdir()
    if (!tmpUploadDir.exists())
        tmpUploadDir.mkdir()

    if (configFile.exists()) {
        try {
            config = objMapper.readValue(configFile, JukeboxConfig::class.java)
        } catch(jsonError: JSONException) {
            error("Something went wrong reading the config file ($configFile): $jsonError")
        }
    } else
        println("Config file ($configFile) does not exist, using default values (see $projectHosting for configuration details)")

    if (config.ip.contains("\$ip") || config.ip.contains("\$port"))
        config.ip = config.ip.replace("\$ip", getIP()).replace("\$port", "${config.port}")

    if (config.spotifyClient.isPresent && config.spotifySecret.isPresent && config.spotifyBase64.isEmpty) {
        config.spotifyBase64 = b64Encoder.encodeToString("${config.spotifyClient()}:${config.spotifySecret()}".toByteArray(Charsets.UTF_8)).asOptional()
        config.spotifyClient = Optional.empty()
        config.spotifySecret = Optional.empty()
    }

    if (config.spotifyBase64.isEmpty)
        println("Note: Spotify Authentication details are absent; server running in offline/cache mode. New song retrievals will fail (see $projectHosting for more information)!")

    if (!mysqlEnabled())
        println("Note: MySQL details are absent; server running in 'simple' mode. Popular song retrieval will fail, as will logins (see $projectHosting for more information)!")
    else {
        createAccountsTable()
        createPopularJukeboxTable()
        createPopularCanonizerTable()
    }

    if (isInsecure()) {
        if (config.httpsOverride)
            errPrintln("You've attempted to use secure features of this program, such as logins, while not using security features for them (HTTPS, Secure Cookies). See $projectHosting for more information.\nYou've then opted to override this using httpsOverride. This is not a good idea. I repeat, do *not* use this in a production environment without an exceptionally good reason!")
        else
            forceError("Error: You've attempted to use secure features of this program, such as logins, while not using security features for them (HTTPS, Secure Cookies). See $projectHosting for more information.\nIf you absolutely *must* override this (development environment), then you can override this in the config file with the key httpsOverride. Do not do this in a production environment, short of some other protocol to handle security (eg: CloudFlare)")
    }

    if (config.devMode) {
        System.setProperty("vertx.disableFileCPResolving", "true")
    }

    val vertx = Vertx.vertx()
    val options = HttpServerOptions()

    config.ssl.ifPresent { (key, cert) ->
        options.pemKeyCertOptions = PemKeyCertOptions().setKeyPath(key).setCertPath(cert)
        options.isSsl = true
    }

    val http = vertx.createHttpServer(options)

    val router = Router.router(vertx)

    if (config.uploads) {
        router.post().blockingHandler {
            checkStorage()
            it.next()
        }
        router.post().handler(BodyHandler.create(tmpUploadDir.name).setDeleteUploadedFilesOnEnd(true).setBodyLimit(10 * 1000 * 1000))
    }

    if (config.logAllPaths && mysqlEnabled()) {
        createRequestsTable()
        router.route().handler(::logRequest)
    }

    if (config.uploads) {
        router.post("/upload/song").blockingHandler { context ->
            if (context.fileUploads().isNotEmpty()) {
                val file = context.fileUploads().first()
                val id = "CSTM${b64Encoder.encodeToString(file.uploadedFileName().toByteArray(Charsets.UTF_8))}"
                val song = File(audioDir, "$id.${config.format}")

                val process = ProcessBuilder()
                        .command("ffmpeg", "-i", file.uploadedFileName(), song.absolutePath)
                        .redirectErrorStream(true)
                        .redirectOutput(File(logDir, "$id.log"))
                        .start()

                process.waitFor()
                if (song.exists())
                    context.response().end(id)
                else
                    context.response().setStatusCode(404).end()

            }
        }
    }

    if (config.cors)
        router.route().handler(CorsHandler.create("*"))

    if (anyLogins()) {
        router.route().handler(CookieHandler.create())
        router.route().handler(SessionHandler
                .create(LocalSessionStore.create(vertx))
                .setCookieHttpOnlyFlag(config.httpOnlyCookies)
                .setCookieSecureFlag(config.secureCookies)
        )

        if (config.csrf)
            router.route("/user/").handler(CSRFHandler.create(config.csrfSecret))
    }

    router.route().handler { context ->
        if (config.redirects.containsKey(context.request().path()))
            context.reroute(config.redirects[context.request().path()])
        else
            context.next()
    }
    config.searchEndpoint.ifPresent { endpoint -> router.route(endpoint).blockingHandler(::search) }
    config.idEndpoint.ifPresent { endpoint -> router.route(endpoint).blockingHandler(::id) }
    config.audioEndpoint.ifPresent { endpoint -> router.route(endpoint).blockingHandler(::audio) }
    config.songEndpoint.ifPresent { endpoint -> router.route(endpoint).blockingHandler(::song) }

    config.fileManager.ifPresent { (first, second) -> router.route(first).handler(StaticHandler.create(second)) }

    router.route(config.retroIndexEndpoint, "retro_index.html")
    router.route(config.faqEndpoint, "faq.html")

    router.route(config.jukeboxIndexEndpoint, "jukebox_index.html")
    router.popularRoute(config.jukeboxGoEndpoint, "jukebox_go.html", true)
    router.route(config.jukeboxSearchEndpoint, "jukebox_search.html")

    router.route(config.canonizerIndexEndpoint, "canonizer_index.html")
    router.popularRoute(config.canonizerGoEndpoint, "canonizer_go.html", false)
    router.route(config.canonizerSearchEndpoint, "canonizer_search.html")

    router.get("/robots.txt").handler { context -> context.response().end(config.robotsTxt) }

    config.apiBreakdownEndpoint.ifPresent { endpoint -> router.route(endpoint).blockingHandler(::api) }

    config.loginEndpoint.ifPresent { endpoint -> router.route(endpoint).handler { context -> context.response().sendFile("login.html") } }

    config.popularJukeboxTracksEndpoint.ifPresent { endpoint -> router.route(endpoint).blockingHandler(::popularJukebox) }
    config.popularCanonizerTracksEndpoint.ifPresent { endpoint -> router.route(endpoint).blockingHandler(::popularCanonizer) }

    config.faviconPath.ifPresent { favicon -> router.route("/favicon.ico").handler(FaviconHandler.create(favicon)) }
    config.appleTouchIconPath.ifPresent { touchIcon -> router.route("/apple-touch-icon.png").handler { it.response().sendFile(touchIcon) } }

    if (allowDiscordLogins()) {
        val discordOAuth2 = OAuth2Auth.create(vertx, OAuth2FlowType.AUTH_CODE, OAuth2ClientOptions()
                .setClientID(config.discordClient())
                .setClientSecret(config.discordSecret())
                .setSite("https://discordapp.com")
                .setTokenPath("/api/oauth2/token")
                .setAuthorizationPath("/api/oauth2/authorize"))
        router.route("/logins/discord").handler(UserSessionHandler.create(discordOAuth2))
        router.route("/callbacks/discord").handler(UserSessionHandler.create(discordOAuth2))

        val oauth2 = OAuth2AuthHandler.create(discordOAuth2, config.ip).setupCallback(router.get("/callbacks/discord")).addAuthority("identify")

        router.route("/logins/discord").handler(oauth2)
        router.route("/logins/discord").handler { context ->
            println("Successfully authenticated through Discord, redirecting back to ${context.session()["redirect-login"] ?: "login.html"} after account information is checked")

            context.token().use { token ->
                val id = Unirest.get("https://discordapp.com/api/users/@me").header("Authorization", "Bearer $token").asJson().body.`object`["id"] as String
                //val timelord = getOrCreateUser(LoginService.DISCORD, id)
            }
        }
    }

    if (config.logMissingPaths) {
        router.route().handler { context ->
            context.response().setStatusCode(404).end()
            println("Request was sent from ${context.request().remoteAddress()} that didn't match any paths, sending a 404 for ${context.request().path()}")
            sendFirebaseMessage(notification {
                title("[EternalJukebox] Unknown Path")
                body("${context.request().remoteAddress()} requested ${context.request().path()}, returning with 404")
            }.asOptional())
        }
    }

    http.requestHandler { router.accept(it) }.listen(config.port)
    println("Listening at ${config.ip}")
}

fun Router.route(optionalEndpoint: Optional<String>, file: String) = optionalEndpoint.ifPresent { endpoint -> this.route(endpoint).handler { context -> context.response().sendFile(file) } }
fun Router.popularRoute(optionalEndpoint: Optional<String>, file: String, jukebox: Boolean) = optionalEndpoint.ifPresent { endpoint ->
    this.route(endpoint).handler { context ->
        context.response().sendFile(file)
        if(jukebox)
            populariseJukebox(context)
        else
            populariseCanonizer(context)
    }
}

fun makeConnection(): PoolableObject<Connection> = PoolableObject(DriverManager.getConnection("jdbc:mysql://localhost/${config.mysqlDatabase()}?user=${config.mysqlUsername()}&password=${config.mysqlPassword()}&serverTimezone=GMT"))

fun getSnowflake(): String = Snowstorm.getInstance(config.epoch).get()

fun checkStorage() {
    var totalUsed = 0L
    val allFiles = ArrayList<File>()

    allFiles.addAll(eternalDir.listFiles().filter(File::isFile))
    allFiles.addAll(songsDir.listFiles().filter(File::isFile))
    allFiles.addAll(audioDir.listFiles().filter(File::isFile))

    allFiles.sortedWith(Comparator<File> { file1, file2 -> file1.lastModified().compareTo(file2.lastModified()) })
    allFiles.forEach { file -> totalUsed += file.length() }

    if (totalUsed > config.storageEmergency) {
        sendFirebaseMessage(notification {
            title("[EternalJukebox] Emergency Storage Reached")
            body("EternalJukebox has $totalUsed B used (${ByteUnit(totalUsed).toMegabytes()} MB)")
        }.asOptional())
    }

    if (totalUsed > config.storageSize) {
        allFiles.forEach { file ->
            if (totalUsed > config.storageBuffer) {
                println("$totalUsed > ${config.storageBuffer}")
                totalUsed -= file.length()
                file.delete()
                println("Deleted $file")
            }
        }
    }
}

fun sendFirebaseMessage(notificationPayload: Optional<NotificationPayload> = Optional.empty(), dataPayload: Optional<JSONObject> = Optional.empty()) {
    config.firebaseApp.ifPresent { token ->
        config.firebaseDevice.ifPresent { device ->
            val payload = JSONObject()
            payload.put("to", device)
            notificationPayload.ifPresent { notification -> payload.put("notification", JSONObject(objMapper.writeValueAsString(notification))) }
            dataPayload.ifPresent { data -> payload.put("data", data) }
            val response = Unirest.post("https://fcm.googleapis.com/fcm/send").header("Authorization", "key=$token").jsonBody(payload).asJson().body.toJsonObject()
        }
    }
}

var bearer = ""
var expires: Instant = Instant.now()

fun popularJukebox(context: RoutingContext) {
    val request = context.request()
    val popular = getPopularJukeboxSongs((request.getParam("count") ?: "30").toIntOrNull() ?: 30)
    val array = JSONArray()

    popular.forEach { id ->
        eternalForID(id).ifPresent { (info) ->
            val track = JSONObject()

            track.put("id", info.id)
            track.put("name", info.name)
            track.put("title", info.name) //Just in case
            track.put("artist", info.artist)
            track.put("url", info.url)

            array.put(track)
        }
    }

    context.response().putHeader("Content-Type", "application/json").end(array.toString())
}
fun popularCanonizer(context: RoutingContext) {
    val request = context.request()
    val popular = getPopularCanonizerSongs((request.getParam("count") ?: "30").toIntOrNull() ?: 30)
    val array = JSONArray()

    popular.forEach { id ->
        eternalForID(id).ifPresent { (info) ->
            val track = JSONObject()

            track.put("id", info.id)
            track.put("name", info.name)
            track.put("title", info.name) //Just in case
            track.put("artist", info.artist)
            track.put("url", info.url)

            array.put(track)
        }
    }

    context.response().putHeader("Content-Type", "application/json").end(array.toString())
}

fun search(context: RoutingContext) {
    val request = context.request()

    if (!config.spotifyBase64.isPresent) {
        context.response().setStatusCode(501).putHeader("Content-Type", "application/json").end("{\"error\":\"Server not configured for new Spotify requests; bug the administrator\"}")
        return
    }

    if (expires.isBefore(Instant.now()))
        reloadSpotifyToken()

    for (i in 0 until 3) {
        try {
            val array = JSONArray()

            val trackInfo = Unirest.get("https://api.spotify.com/v1/search")
                    .queryString("q", request.getParam("q") ?: "Never Gonna Give You Up")
                    .queryString("type", "track")
                    .queryString("limit", request.getParam("results") ?: "30")
                    .header("Authorization", "Bearer $bearer")
                    .asJson()

            if(trackInfo.status >= 300) {
                context.response().setStatusCode(trackInfo.status).putHeader("Content-Type", "application/json").end("{\"error\":\"Spotify responded with a status code of ${trackInfo.status} and an error of ${trackInfo.statusText}\"}")
                return
            }

            for (item in trackInfo.body.`object`.getJSONObject("tracks").getJSONArray("items")) {
                val obj = item as JSONObject
                val track = JSONObject()

                track.put("id", obj["id"])
                track.put("name", obj["name"])
                track.put("title", obj["name"]) //Just in case
                track.put("artist", ((obj["artists"] as JSONArray)[0] as JSONObject)["name"])
                track.put("url", "${config.ip}/song?id=${obj["id"]}")

                array.put(track)
            }

            context.response().putHeader("Content-Type", "application/json").end(array.toString())
            break
        } catch(json: JSONException) {
            error("An error occurred while parsing JSON for search: $json")
            continue
        }
    }
}

fun song(context: RoutingContext) {
    val request = context.request()

    val id = request.getParam("id").replace("[^A-Za-z0-9]".toRegex(), "")
    val song = songForID(id)
    if (song.isPresent)
        context.response().sendFile(song.get().absolutePath)
    else
        context.response().setStatusCode(404).end()
}

fun songForID(id: String): Optional<File> {
    val file = File(songsDir, "$id.${config.format}")
    if (file.exists())
        return file.asOptional()
    else {
        checkStorage()
        for (i in 0 until 3) {
            try {
                val trackInfo = Unirest.get("https://api.spotify.com/v1/tracks/$id").header("Authorization", "Bearer $bearer").asObject(SpotifyTrack::class.java)
                if (trackInfo.status != 200) {
                    println("Non 200 code for song for $id; iteration $i")
                    continue
                }
                val track = trackInfo.body

                val name = track.name
                val artist = track.artists[0].name
                val duration = track.duration_ms
                val results = searchYoutube("$artist - $name")
                if (results.isEmpty()) {
                    error("[$artist - $name] produced *no* results (See? $results)")
                    return Optional.empty()
                }

                val closestResult = if (true) results[0].id else results.sortedWith(Comparator<YoutubeVideo> { (_, duration1), (_, duration2) -> Math.abs(duration - duration1.toMillis()).compareTo(Math.abs(duration - duration2.toMillis())) }).first().id
                val process = ProcessBuilder()
                        .command("bash", "yt.sh", "https://youtu.be/$closestResult", file.absolutePath, config.format)
                        .redirectErrorStream(true)
                        .redirectOutput(File(logDir, "$id.log"))
                        .start()

                if (process.waitFor(50, TimeUnit.SECONDS)) {
                    if (file.exists())
                        return file.asOptional()
                    else
                        return Optional.empty()
                } else
                    return Optional.empty()
            } catch(json: JSONException) {
                error("An error occured while parsing JSON for song of id $id: $json")
                continue
            }
        }
    }

    return Optional.empty()
}

val temporalComponents = arrayOf(ChronoUnit.SECONDS, ChronoUnit.MINUTES, ChronoUnit.HOURS, ChronoUnit.DAYS)

fun searchYoutube(search: String): Array<YoutubeVideo> {
    val html = Jsoup.parse(Unirest.get("https://www.youtube.com/results").queryString("search_query", URLEncoder.encode(search, "UTF-8")).asString().body)
    val listElements = html.select("ol.item-section").select("li").map { li -> li.select("div.yt-lockup-dismissable") }
    val videos = ArrayList<YoutubeVideo>(listElements.size)
    listElements.forEach { ytVid ->
        val id = ytVid.select("a[href]").attr("href")
        if (id.isNotBlank()) {
            val ytID = id.substringAfter("=").substring(0, 11)
            val videoTime = ytVid.select("span.video-time").singleOrNull()?.text() ?: "1:00"
            var duration = Duration.ZERO
            videoTime.split(':').reversed().forEachIndexed { index, s -> duration = duration.plus(s.toLong(), temporalComponents[index]) }
            videos.add(YoutubeVideo(ytID, duration))
        }
    }
    return videos.toTypedArray()
}

fun audio(context: RoutingContext) {
    val request = context.request()

    val url = request.getParam("url") ?: "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    if (url.matches(b64CustomID)) {
        val file = File(audioDir, "$url.${config.format}")
        if (file.exists()) {
            context.response().sendFile(file.absolutePath)
            return
        }
    }

    val b64 = b64Encoder.encodeToString(url.toByteArray(Charsets.UTF_8)).replace("/", "-")
    val file = File(audioDir, "$b64.${config.format}")

    if (file.exists()) {
        context.response().sendFile(file.absolutePath)
        return
    }

    checkStorage()
    val process = ProcessBuilder()
            .command("bash", "yt.sh", url, file.absolutePath, config.format)
            .redirectErrorStream(true)
            .redirectOutput(File(logDir, "$b64.log"))
            .start()

    process.waitFor()
    if (file.exists())
        context.response().sendFile(file.absolutePath)
    else {
        val fallback = songForID(request.getParam("fallback") ?: "")
        if (fallback.isPresent)
            context.response().sendFile(fallback.get().absolutePath)
        else
            context.response().setStatusCode(404).end()
    }
}

fun api(context: RoutingContext) {
    val request = context.request()

    val id = request.getParam("id").replace("[^A-Za-z0-9]".toRegex(), "")

    if (!config.spotifyBase64.isPresent) {
        context.response().setStatusCode(501).putHeader("Content-Type", "application/json").end("{\"error\":\"Server not configured for new Spotify requests; bug the administrator\"}")
        return
    }

    if (expires.isBefore(Instant.now()))
        reloadSpotifyToken()

    try {
        val file = File(eternalDir, "$id.json")
        if (!file.exists()) {
            val trackInfo = Unirest.get("https://api.spotify.com/v1/tracks/$id").header("Authorization", "Bearer $bearer").asObject(SpotifyTrack::class.java)
            if (trackInfo.status != 200) {
                context.response().setStatusCode(404).putHeader("Content-Type", "application/json").end("{\"error\":\"Spotify returned a status code of ${trackInfo.status} with error ${trackInfo.statusText}\"}")
                return
            }
            val acousticInfo = Unirest.get("https://api.spotify.com/v1/audio-analysis/$id").header("Authorization", "Bearer $bearer").asObject(SpotifyAudio::class.java)
            if (trackInfo.status != 200) {
                context.response().setStatusCode(trackInfo.status).end()
                return
            }
            val trackInfoBody = trackInfo.body
            val track = acousticInfo.body

            val eternal = EternalAudio(
                    EternalInfo(
                            trackInfoBody.id,
                            trackInfoBody.name,
                            trackInfoBody.name,
                            trackInfoBody.artists[0].name,
                            "${config.ip}/song?id=$id"
                    ),
                    EternalAnalysis(
                            track.sections,
                            track.bars,
                            track.beats,
                            track.tatums,
                            track.segments
                    ),
                    track.track
            )
            objMapper.writeValue(FileOutputStream(file), eternal)
        }
        val eternal = objMapper.readValue(file, EternalAudio::class.java)

        preprocessTrack(eternal)

//        val x_padding = 90
//        val y_padding = 200
//        val maxWidth = 90
//        val radius = 500
//        val qlist = eternal.analysis.beats
//        var n = qlist.size.toDouble()
//        var R = radius
//        var alpha = Math.PI * 2.0 / n.toDouble()
//        var perimeter = 2 * n * R * Math.sin(alpha / 2.0)
//        var a = perimeter / n
//        var width = (a * 2).coerceAtMost(maxWidth.toDouble()).toInt()
//        var angleOffset = - Math.PI / 2.0
//
//        val img = BufferedImage(x_padding + R, y_padding + R, BufferedImage.TYPE_INT_ARGB)
//        val g = img.createGraphics()
//
//        var angle = angleOffset
//        val rng = Random()
//        for(q in qlist) {
//            val x = x_padding + R + R * Math.cos(angle)
//            val y = y_padding + R + R * Math.sin(angle)
//            g.color = Color(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
//            val transform = AffineTransform()
//            val rect = Rectangle(x.toInt(), y.toInt(), width, a.toInt())
//
//            transform.translate(rect.x / 2.0, rect.y / 2.0)
//            transform.rotate(Math.toRadians(360 * (angle / (Math.PI * 2))))
//            transform.translate((-rect.x).toDouble(), (-rect.y).toDouble())
//
//            val transformed = transform.createTransformedShape(rect)
//            g.fill(transformed)
//            angle += alpha
//        }
//
//        val f = File("$id.png")
//        ImageIO.write(img, "PNG", f)
//        context.response().sendFile(f.absolutePath)

    } catch(json: RuntimeException) {
        error("An error occurred while parsing JSON for the api: $json")
        json.printStackTrace()
    } catch(th: Throwable) {
        error("An unexpected error occurred: $th")
    }
}

/** Wants track information and acoustics */
fun id(context: RoutingContext) {
    val request = context.request()

    val id = request.getParam("id").replace("[^A-Za-z0-9]".toRegex(), "")

    if (!config.spotifyBase64.isPresent) {
        context.response().setStatusCode(501).putHeader("Content-Type", "application/json").end("{\"error\":\"Server not configured for new Spotify requests; bug the administrator\"}")
        return
    }

    val eternal = eternalForID(id)
    if(eternal.isPresent)
        context.response().putHeader("Content-Type", "application/json").end(objMapper.writeValueAsString(eternal()))
    else
        context.response().setStatusCode(404).end()
}

fun eternalForID(id: String): Optional<EternalAudio> {
    val file = File(eternalDir, "$id.json")
    if (file.exists())
        return objMapper.readValue(file, EternalAudio::class.java).asOptional()
    else {
        checkStorage()
        if (expires.isBefore(Instant.now()))
            reloadSpotifyToken()

        for (i in 0 until 3) {
            try {
                val trackInfo = Unirest.get("https://api.spotify.com/v1/tracks/$id").header("Authorization", "Bearer $bearer").asObject(SpotifyTrack::class.java)
                if (trackInfo.status != 200) {
                    println("Non 200 status code for track retrieval (Code: ${trackInfo.status}, error ${trackInfo.statusText}) for ID $id and iteration $i")
                    return Optional.empty()
                }
                val acousticInfo = Unirest.get("https://api.spotify.com/v1/audio-analysis/$id").header("Authorization", "Bearer $bearer").asObject(SpotifyAudio::class.java)
                if (acousticInfo.status != 200) {
                    println("Non 200 status code for track retrieval (Code: ${acousticInfo.status}, error ${acousticInfo.statusText}) for ID $id and iteration $i")
                    return Optional.empty()
                }

                val trackInfoBody = trackInfo.body
                val track = acousticInfo.body

                val eternal = EternalAudio(
                        EternalInfo(
                                trackInfoBody.id,
                                trackInfoBody.name,
                                trackInfoBody.name,
                                trackInfoBody.artists[0].name,
                                "${config.ip}/song?id=$id"
                        ),
                        EternalAnalysis(
                                track.sections,
                                track.bars,
                                track.beats,
                                track.tatums,
                                track.segments
                        ),
                        track.track
                )

                objMapper.writeValue(FileOutputStream(file), eternal)
                return eternal.asOptional()
            } catch(json: RuntimeException) {
                error("An error occurred while parsing JSON for retrieving eternal song $id: $json")
                continue
            } catch(th: Throwable) {
                error("An unexpected error occurred: $th")
            }
        }
    }

    return Optional.empty()
}

val executor: ExecutorService = Executors.newCachedThreadPool()

fun RoutingContext.token(): AccessToken = if (user() is AccessToken) user() as AccessToken else throw IllegalArgumentException("User is not an Access Token!")
fun AccessToken.refresh(): String {
    var finished = false
    refresh { finished = true }
    while (!finished) Thread.sleep(1000)

    return principal().getString("access_token")
}

fun AccessToken.accessToken(): String = if (expired()) refresh() else principal().getString("access_token")
fun <T> AccessToken.use(action: (String) -> T): Future<T> = executor.submit(Callable<T> { action.invoke(accessToken()) })

fun reloadSpotifyToken() {
    config.spotifyBase64.ifPresent { base64 ->
        val token = Unirest
                .post("https://accounts.spotify.com/api/token")
                .header("Authorization", "Basic $base64")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=client_credentials")
                .asJson()
                .body.`object`
        bearer = token["access_token"] as String
        expires = Instant.ofEpochMilli(System.currentTimeMillis() + (token["expires_in"] as Int).times(1000))
    }
}

fun allowDiscordLogins(): Boolean = config.discordClient.isPresent && config.discordSecret.isPresent && mysqlEnabled()
fun anyLogins(): Boolean = allowDiscordLogins()
fun mysqlEnabled(): Boolean = config.mysqlDatabase.isPresent && config.mysqlUsername.isPresent && config.mysqlPassword.isPresent

fun <T : HttpRequestWithBody> T.jsonBody(json: JSONObject): T {
    header("Content-Type", "application/json")
    body(json.toString())
    return this
}

fun JsonNode.toJsonObject(): JSONObject = JSONObject(`object`.toString())

fun error(msg: Any) {
    errPrintln(msg)
    sendFirebaseMessage(notification {
        title("[EternalJukebox] Error")
        body(msg.toString())
    }.asOptional())
}

fun isInsecure(): Boolean = anyLogins() && (!config.secureCookies || config.ssl.isEmpty)