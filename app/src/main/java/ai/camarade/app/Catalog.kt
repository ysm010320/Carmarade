package ai.camarade.app

import ai.camarade.core.*

object Catalog {
    val places = listOf(
        Place("anmok", "강릉 안목해변", 37.7712, 128.9487),
        Place("hoengseong", "횡성휴게소", 37.4600, 128.1341),
        Place("daegwallyeong", "대관령 양떼목장", 37.6888, 128.7541),
        Place("yangyang", "양양 서피비치", 38.0268, 128.7165),
        Place("chuncheon", "춘천 의암호", 37.8380, 127.6744),
        Place("yangpyeong", "양평 두물머리", 37.5315, 127.3102),
        Place("gapyeong", "가평휴게소", 37.7410, 127.4094),
        Place("seoul", "서울숲 주차장", 37.5445, 127.0374)
    )
    fun sample(): Trip = Trip.create("주말엔, 동해", "크루", places[0])
        .addMember("me", "jisu", "지수", "IONIQ 5")
        .addMember("me", "haneul", "하늘", "AVANTE")
        .setRoute("me", places[0], listOf(places[1]))
}
