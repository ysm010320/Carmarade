package ai.camarade.app

import android.content.Context
import ai.camarade.core.*
import org.json.JSONArray
import org.json.JSONObject

class TripStore(context: Context) {
    private val prefs = context.getSharedPreferences("camarade_v1", Context.MODE_PRIVATE)
    data class Loaded(val trips: List<Trip>, val error: String? = null)
    fun load(): Loaded {
        val raw = prefs.getString("trips", null) ?: return Loaded(listOf(Catalog.sample()))
        return try {
            val document = JSONObject(raw)
            require(document.getInt("version") == 1)
            val a = document.getJSONArray("trips")
            Loaded((0 until a.length()).map { decode(a.getJSONObject(it)).restoreSafely() })
        } catch (_: Exception) {
            Loaded(emptyList(), "저장된 데이터를 읽지 못했어요. 기존 저장본은 보존되어 있습니다. 새 모임을 만들면 저장본을 교체합니다.")
        }
    }
    fun save(trips: List<Trip>) {
        val doc = JSONObject().put("version", 1).put("trips", JSONArray(trips.map(::encode)))
        check(prefs.edit().putString("trips", doc.toString()).commit()) { "저장 공간이 부족해 변경 내용을 저장하지 못했어요." }
    }
    private fun place(p: Place) = JSONObject().put("id",p.id).put("name",p.name).put("lat",p.latitude).put("lon",p.longitude)
    private fun place(j: JSONObject) = Place(j.getString("id"),j.getString("name"),j.getDouble("lat"),j.getDouble("lon"))
    private fun encode(t: Trip) = JSONObject().apply {
        put("id",t.id); put("name",t.name); put("type",t.type); put("phase",t.phase.name)
        put("created",t.createdAt); put("started",t.startedAt ?: JSONObject.NULL); put("ended",t.endedAt ?: JSONObject.NULL)
        put("destination",place(t.route.destination)); put("waypoints",JSONArray(t.route.waypoints.map(::place))); put("routeVersion",t.route.version)
        put("members",JSONArray(t.members.map { m -> JSONObject().put("id",m.id).put("name",m.name).put("car",m.car).put("accepted",m.acceptedVersion) }))
        put("messages",JSONArray(t.messages.map { m -> JSONObject().put("sender",m.sender).put("recipient",m.recipient).put("text",m.text).put("at",m.at) }))
        t.record?.let { put("record",JSONObject().put("seconds",it.durationSeconds).put("distance",it.distanceKm).put("review",it.review)) }
    }
    private fun decode(j: JSONObject): Trip {
        val w=j.getJSONArray("waypoints"); val m=j.getJSONArray("members"); val msgs=j.getJSONArray("messages")
        val r=j.optJSONObject("record")
        return Trip(j.getString("name"),j.getString("type"),
            RoutePlan(place(j.getJSONObject("destination")),(0 until w.length()).map { place(w.getJSONObject(it)) },j.getInt("routeVersion")),
            (0 until m.length()).map { m.getJSONObject(it).let { x -> Member(x.getString("id"),x.getString("name"),x.getString("car"),Sharing.OFF,x.getInt("accepted")) } },
            Phase.valueOf(j.getString("phase")),
            (0 until msgs.length()).map { msgs.getJSONObject(it).let { x -> Message(x.getString("sender"),x.getString("recipient"),x.getString("text"),x.getLong("at")) } },
            r?.let { TripRecord(it.getLong("seconds"),it.getDouble("distance"),it.getString("review")) },
            j.getString("id"),j.getLong("created"),if(j.isNull("started")) null else j.getLong("started"),if(j.isNull("ended")) null else j.getLong("ended"))
    }
}
