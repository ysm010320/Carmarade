package ai.camarade.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.camarade.core.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RemoteMember(val id: String, val name: String, val car: String, val role: String)
data class RemoteRoom(
    val id: String, val title: String, val kind: String, val visibility: String,
    val destination: Place, val waypoints: List<Place>,
    val inviteCode: String?, val hostId: String?, val members: List<RemoteMember>,
    val memberCount: Int, val maxMembers: Int, val hostName: String, val joined: Boolean, val distanceKm: Double,
    /** 모집 글(선택). */
    val note: String?=null,
    /** 출발 예정 시각(ms, 선택). */
    val departAt: Long?=null
)
data class RemoteLocation(val userId: String, val lat: Double, val lon: Double, val approximate: Boolean, val at: Long)

/** 실시간 연결에서 오는 일. 모두 메인 스레드에서 불린다. */
interface LiveEvents {
    fun onConnected(connected: Boolean) {}
    fun onSnapshot(members: List<RemoteMember>, locations: List<RemoteLocation>) {}
    fun onMembers(members: List<RemoteMember>) {}
    /** 방장이 목적지·경유지를 바꿈. */
    fun onRoom(room: RemoteRoom) {}
    fun onLocation(location: RemoteLocation) {}
    fun onLocationRemoved(userId: String) {}
    fun onMessage(from: String, recipient: String, text: String, at: Long) {}
    fun onRoomClosed() {}
    fun onError(message: String) {}
}

/**
 * 카마라드 방 서버 연결. 가입(닉네임) → 방 만들기/같은 목적지 방 찾기/초대 코드 입장 → 실시간 위치·메시지.
 * 토큰은 이 기기에만 저장하고, 401을 받으면 지운 뒤 다시 등록하게 한다.
 */
class RoomServer(context: Context) {
    private val prefs=context.getSharedPreferences("camarade_server",Context.MODE_PRIVATE)
    private val main=Handler(Looper.getMainLooper())
    private val http=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS)
        .pingInterval(20,TimeUnit.SECONDS).build()
    private val json="application/json; charset=utf-8".toMediaType()

    var baseUrl: String
        get()=prefs.getString("url",DEFAULT_URL) ?: DEFAULT_URL
        set(v) { prefs.edit().putString("url",v.trim().trimEnd('/')).apply() }
    val userId: String? get()=prefs.getString("userId",null)
    val nickname: String? get()=prefs.getString("name",null)
    val car: String? get()=prefs.getString("car",null)
    private val token: String? get()=prefs.getString("token",null)
    val signedIn: Boolean get()=token!=null && userId!=null

    class ServerError(message: String, val status: Int=0) : Exception(message)

    fun register(name: String, car: String, done: (Result<Unit>) -> Unit) =
        call("POST","/v1/users",JSONObject().put("name",name).put("car",car),auth=false,done=done) { j ->
            val u=j.getJSONObject("user")
            prefs.edit().putString("token",j.getString("token")).putString("userId",u.getString("id"))
                .putString("name",u.getString("name")).putString("car",u.getString("car")).apply()
        }
    fun createRoom(title: String, crew: Boolean, public: Boolean, route: RoutePlan, note: String?, departAt: Long?, done: (Result<RemoteRoom>) -> Unit) =
        call("POST","/v1/rooms",JSONObject().put("title",title).put("kind",if(crew) "crew" else "trip")
            .put("visibility",if(public) "public" else "private").put("destination",place(route.destination))
            .put("waypoints",JSONArray(route.waypoints.map(::place)))
            .apply { note?.takeIf { it.isNotBlank() }?.let { put("note",it) };departAt?.let { put("departAt",it) } },done=done) { room(it.getJSONObject("room")) }
    /** 커뮤니티: 지금 모집 중인 공개 방. [query]는 방 이름·목적지·모집 글 검색어. */
    fun openRooms(query: String, done: (Result<List<RemoteRoom>>) -> Unit) =
        call("GET","/v1/rooms/open?q="+java.net.URLEncoder.encode(query.trim(),"UTF-8"),null,done=done) { j ->
            val a=j.getJSONArray("rooms"); (0 until a.length()).map { room(a.getJSONObject(it)) } }
    /** 같은 목적지(반경 1km)의 공개 방. */
    fun sameDestination(p: Place, done: (Result<List<RemoteRoom>>) -> Unit) =
        call("GET","/v1/rooms?lat=${p.latitude}&lon=${p.longitude}",null,done=done) { j ->
            val a=j.getJSONArray("rooms"); (0 until a.length()).map { room(a.getJSONObject(it)) } }
    fun joinPublic(roomId: String, done: (Result<RemoteRoom>) -> Unit) =
        call("POST","/v1/rooms/$roomId/join",JSONObject(),done=done) { room(it.getJSONObject("room")) }
    fun joinByCode(code: String, done: (Result<RemoteRoom>) -> Unit) =
        call("POST","/v1/join",JSONObject().put("code",code),done=done) { room(it.getJSONObject("room")) }
    fun room(roomId: String, done: (Result<RemoteRoom>) -> Unit) =
        call("GET","/v1/rooms/$roomId",null,done=done) { room(it.getJSONObject("room")) }
    /** 방장만: 목적지·경유지 바꾸기. 연결된 멤버에게 바로 알려진다. */
    fun setRoute(roomId: String, route: RoutePlan, done: (Result<RemoteRoom>) -> Unit) =
        call("POST","/v1/rooms/$roomId/route",JSONObject().put("destination",place(route.destination))
            .put("waypoints",JSONArray(route.waypoints.map(::place))),done=done) { room(it.getJSONObject("room")) }
    fun leave(roomId: String, done: (Result<Unit>) -> Unit) = call("POST","/v1/rooms/$roomId/leave",JSONObject(),done=done) { }

    fun live(roomId: String, events: LiveEvents): LiveLink = LiveLink(roomId,events).also { it.open() }

    private fun <T> call(method: String, path: String, body: JSONObject?, auth: Boolean=true, done: (Result<T>) -> Unit, parse: (JSONObject) -> T) {
        val t=token
        if(auth && t==null) { done(Result.failure(ServerError("먼저 닉네임을 등록해 주세요.",401)));return }
        val req=try {
            Request.Builder().url(baseUrl+path).apply {
                if(auth) header("Authorization","Bearer $t")
                if(method=="GET") get() else method(method,(body ?: JSONObject()).toString().toRequestBody(json))
            }.build()
        } catch(e: IllegalArgumentException) { done(Result.failure(ServerError("서버 주소를 확인해 주세요.")));return }
        http.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { done(Result.failure(ServerError("서버에 연결하지 못했어요. 주소와 네트워크를 확인해 주세요."))) } }
            override fun onResponse(call: Call, response: Response) {
                val result: Result<T> = response.use { r ->
                    try {
                        val j=JSONObject(r.body?.string().orEmpty().ifEmpty { "{}" })
                        if(r.code==401 && auth) prefs.edit().remove("token").remove("userId").apply()
                        if(!r.isSuccessful) Result.failure(ServerError(j.optJSONObject("error")?.optString("message") ?: "서버 오류(${r.code})",r.code))
                        else Result.success(parse(j))
                    } catch(e: Exception) { Result.failure(ServerError("서버 응답을 읽지 못했어요.")) }
                }
                main.post { done(result) }
            }
        })
    }

    private fun place(p: Place)=JSONObject().put("placeId",p.id).put("name",p.name).put("lat",p.latitude).put("lon",p.longitude)
    private fun place(j: JSONObject): Place {
        val lat=j.getDouble("lat"); val lon=j.getDouble("lon")
        val id=j.optString("placeId","").ifEmpty { "srv_%.4f_%.4f".format(java.util.Locale.ROOT,lat,lon) }
        return Place(id,j.getString("name"),lat,lon)
    }
    private fun members(a: JSONArray?)=(0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it).let { m -> RemoteMember(m.getString("id"),m.getString("name"),m.getString("car"),m.optString("role","member")) } }
    private fun room(j: JSONObject): RemoteRoom {
        val w=j.optJSONArray("waypoints") ?: JSONArray()
        val ms=members(j.optJSONArray("members"))
        return RemoteRoom(j.getString("id"),j.getString("title"),j.optString("kind","trip"),j.optString("visibility","public"),
            place(j.getJSONObject("destination")),(0 until w.length()).map { place(w.getJSONObject(it)) },
            j.optString("inviteCode","").ifEmpty { null },j.optString("hostId","").ifEmpty { null },ms,
            j.optInt("memberCount",ms.size),j.optInt("maxMembers",5),j.optString("hostName",""),j.optBoolean("joined",false),j.optDouble("distanceKm",0.0),
            if(j.isNull("note")) null else j.optString("note","").ifEmpty { null },
            if(j.isNull("departAt")) null else j.optLong("departAt").takeIf { it>0 })
    }

    /** 방 하나의 실시간 연결. 끊기면 2·4·8…초 간격(최대 30초)으로 다시 붙는다. */
    inner class LiveLink(val roomId: String, private val events: LiveEvents) {
        private var socket: WebSocket?=null
        private var closed=false
        private var retry=0
        private val reconnect=Runnable { open() }

        fun open() {
            if(closed) return
            val t=token
            if(t==null) { main.post { events.onError("먼저 닉네임을 등록해 주세요.") };return }
            val url=baseUrl.replaceFirst("http","ws")+"/v1/live"
            val req=try { Request.Builder().url(url).header("Authorization","Bearer $t").build() } catch(_: IllegalArgumentException) { main.post { events.onError("서버 주소를 확인해 주세요.") };return }
            socket=http.newWebSocket(req,object: WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    retry=0;webSocket.send(JSONObject().put("type","subscribe").put("roomId",roomId).toString())
                    main.post { events.onConnected(true) }
                }
                override fun onMessage(webSocket: WebSocket, text: String) { val m=runCatching { JSONObject(text) }.getOrNull() ?: return;main.post { dispatch(m) } }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lost() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost() }
            })
        }
        private fun lost() {
            main.post {
                if(closed) return@post
                events.onConnected(false)
                main.removeCallbacks(reconnect);main.postDelayed(reconnect,(2000L shl retry.coerceAtMost(4)).coerceAtMost(30_000L));retry++
            }
        }
        private fun location(m: JSONObject)=RemoteLocation(m.getString("userId"),m.getDouble("lat"),m.getDouble("lon"),m.optBoolean("approximate"),m.optLong("at"))
        private fun dispatch(m: JSONObject) {
            if(closed || m.optString("roomId",roomId)!=roomId) return
            when(m.optString("type")) {
                "snapshot" -> { val a=m.optJSONArray("locations") ?: JSONArray(); events.onSnapshot(members(m.optJSONArray("members")),(0 until a.length()).map { location(a.getJSONObject(it)) }) }
                "members" -> events.onMembers(members(m.optJSONArray("members")))
                "room" -> events.onRoom(room(m.getJSONObject("room")))
                "location" -> events.onLocation(location(m))
                "location-removed" -> events.onLocationRemoved(m.getString("userId"))
                "message" -> events.onMessage(m.getString("from"),m.getString("recipient"),m.getString("text"),m.optLong("at"))
                "room-closed","removed" -> { events.onRoomClosed();close() }
                "error" -> events.onError(m.optString("message","서버 오류"))
            }
        }
        /** 위치 보내기. sharing은 "exact" | "approximate" | "off". */
        fun sendLocation(lat: Double, lon: Double, heading: Double?, sharing: String) {
            val j=JSONObject().put("type","location").put("roomId",roomId).put("sharing",sharing)
            if(sharing!="off") { j.put("lat",lat).put("lon",lon);heading?.let { j.put("heading",it) } }
            socket?.send(j.toString())
        }
        fun sendMessage(recipient: String, text: String) { socket?.send(JSONObject().put("type","message").put("roomId",roomId).put("recipient",recipient).put("text",text).toString()) }
        /** 위치 공유를 끄고 연결을 닫음. 서버는 연결이 끊기면 위치를 지운다. */
        fun close() {
            if(closed) return
            closed=true;main.removeCallbacks(reconnect)
            socket?.let { it.send(JSONObject().put("type","location").put("roomId",roomId).put("sharing","off").toString());it.close(1000,"bye") }
            socket=null
        }
    }

    companion object {
        /** 에뮬레이터에서 이 PC의 서버(127.0.0.1:8787)로 가는 주소. */
        const val DEFAULT_URL="http://10.0.2.2:8787"
    }
}
