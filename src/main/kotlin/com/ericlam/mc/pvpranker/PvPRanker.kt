package com.ericlam.mc.pvpranker

import com.ericlam.mc.kotlib.KotLib
import com.ericlam.mc.kotlib.bukkit.BukkitPlugin
import com.ericlam.mc.kotlib.command.BukkitCommand
import com.ericlam.mc.kotlib.config.Prefix
import com.ericlam.mc.kotlib.config.Resource
import com.ericlam.mc.kotlib.config.dao.Dao
import com.ericlam.mc.kotlib.config.dao.DataFile
import com.ericlam.mc.kotlib.config.dao.DataResource
import com.ericlam.mc.kotlib.config.dao.PrimaryKey
import com.ericlam.mc.kotlib.config.dto.ConfigFile
import com.ericlam.mc.kotlib.config.dto.MessageFile
import com.ericlam.mc.kotlib.kClassOf
import com.ericlam.mc.kotlib.translateColorCode
import com.ericlam.mc.rankcal.PlayerData
import com.ericlam.mc.rankcal.RankData
import com.ericlam.mc.rankcal.RankDataManager
import com.ericlam.mc.rankcal.implement.RankingLib
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.apache.commons.lang.time.DurationFormatUtils
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.text.MessageFormat.format
import java.util.*
import java.util.concurrent.CompletableFuture

class PvPRanker : BukkitPlugin() {

    private lateinit var rankManager: RankDataManager

    override fun enable() {
        val manager = KotLib.getConfigFactory(this)
                .register(kClassOf<Config>())
                .register(kClassOf<Lang>())
                .registerDao(kClassOf(), kClassOf<PlayerDataController>())
                .dump()
        val controller = manager.getDao(kClassOf<PlayerDataController>())
        val config = manager.getConfig(kClassOf<Config>())
        val lang = manager.getConfig(kClassOf<Lang>())
        val api = RankingLib.getRankAPI()


        fun resetRank() {
            rankManager.doCalculate(config.calculator).thenCombine(rankManager.savePlayerData()) { map, _ ->
                info("成功更新 ${map.size} 個數據")
            }.whenComplete { _, ex -> ex?.printStackTrace() }
        }

        var count = config.countDownSeconds

        fun reload() {
            cancelTasks()
            var i = 0
            config.reload()
            lang.reload()
            val ranks = sortedSetOf(*config.ranks.map { (k, v) -> Ranker(k, v.translateColorCode(), i++) as RankData }.toTypedArray())
            rankManager = api.factory
                    .addPlayers(controller.findAll())
                    .registerRanks(ranks)
                    .registerSaveMechanic {
                        CompletableFuture.runAsync { it.forEach { controller.save { it as Data } } }
                    }
                    .build()
            if (config.countDownSeconds > 0) {
                schedule(async = true, delay = 5, period = 1) {
                    when {
                        count > 0 -> count--
                        count == 0 -> resetRank().also { count = config.countDownSeconds }
                    }
                }
            }
        }

        reload()



        fun getTopBoard(size: Int = rankManager.rankDataMap.size): List<Pair<UUID, Pair<PlayerData?, RankData>>> {
            return rankManager.rankDataMap.asIterable().take(size).map {
                val playerData = rankManager.getPlayerData(it.key)
                Pair(it.key, Pair(playerData, it.value))
            }.filter { it.second.first != null }.sortedBy { it.second.first }
        }

        val command = object : BukkitCommand("ranker", "Ranker Cmmands",
                child = arrayOf(
                        BukkitCommand("settle", "重新結算", "ranker.admin") { sender, _ ->
                            resetRank()
                            sender.sendMessage(lang["rank-settle"])
                        },
                        BukkitCommand("see", "查看別人牌位", "ranker.admin",
                                placeholders = arrayOf("player")) { sender, args ->
                            val data = server.getOffline(args[0])?.let { rankManager.getRankData(it.uniqueId) }
                                    ?: let { sender.sendMessage(format(lang["not-found-player"], args[0])); return@BukkitCommand }
                            sender.sendMessage(format(lang["rank-check"], args[0], data.rankDisplay))
                        },
                        BukkitCommand("reload", "重載插件", "ranker.admin") { sender, _ ->
                            reload().also { sender.sendMessage(lang["reloaded"]) }
                        },
                        BukkitCommand("set", "設置分數", "ranker.admin",
                                placeholders = arrayOf("player", "score")) { sender, args ->
                            val uid = server.getOffline(args[0])?.uniqueId
                                    ?: let { sender.sendMessage(format(lang["not-found-player"], args[0])); return@BukkitCommand }
                            val data = rankManager.getPlayerData(uid)
                                    ?: let { sender.sendMessage(lang["no-data"]); return@BukkitCommand }
                            val score = args[1].toDoubleOrNull()
                                    ?: let { sender.sendMessage(format(lang["not-num"], args[1])); return@BukkitCommand }
                            (data as Data).scores = score
                            rankManager.update(data.playerUniqueId)
                            sender.sendMessage(format(lang["updated"], args[0]))
                        },
                        BukkitCommand("self", "查看自身排位") { sender, _ ->
                            val player = sender as? Player
                                    ?: let { sender.sendMessage(lang["not-player"]); return@BukkitCommand }
                            val rank = rankManager.getRankData(player.uniqueId)
                                    ?: let { sender.sendMessage(lang["no-data"]); return@BukkitCommand }
                            sender.sendMessage(format(lang["rank-self"], rank.rankDisplay))
                        },
                        BukkitCommand("top", "查看排行榜") { sender, _ ->
                            getTopBoard(config.showTop).forEach {
                                sender.sendMessage(format(lang["board-line"], it.first, it.second.first!!.score, it.second.second.rankDisplay))
                            }
                        }
                )) {}

        registerCmd(command)


        listen<PlayerDeathEvent> {
            val player = it.entity
            val killer = player.killer ?: return@listen
            (rankManager.getPlayerData(player.uniqueId) as? Data)?.also { d ->
                d.scores.plus(config.whenKill["player"] ?: 0)
            }?.also { d -> rankManager.update(d.playerUniqueId) }?.also { controller.save { it } }
            (rankManager.getPlayerData(killer.uniqueId) as? Data)?.also { d ->
                d.scores.plus(config.whenKill["killer"] ?: 0)
            }?.also { d -> rankManager.update(d.playerUniqueId) }?.also { controller.save { it } }
        }

        listen<PlayerJoinEvent> { e ->
            val d = rankManager.getPlayerData(e.player.uniqueId)
            if (d == null) {
                val data = controller.findById(e.player.uniqueId) ?: let {
                    val data = Data(e.player.uniqueId)
                    val id = controller.save { data }
                    info("成功創建新數據: $id")
                    data
                }
                rankManager.join(data)
            }
        }


        val papi = object : PlaceholderExpansion() {

            private val NO_DATA
                get() = lang.getPure("no-data")

            override fun getVersion(): String {
                return this@PvPRanker.description.version
            }

            override fun getAuthor(): String {
                return this@PvPRanker.description.authors.toString()
            }

            override fun getIdentifier(): String = "ranks"

            override fun onRequest(p: OfflinePlayer?, params: String?): String {
                p ?: return lang["not-found-player"]

                val rank = rankManager.getRankData(p.uniqueId)
                val user = rankManager.getPlayerData(p.uniqueId)
                return when (params?.toLowerCase()) {
                    "rank" -> rank?.rankDisplay ?: NO_DATA
                    "score" -> user?.score.toString()
                    "top" -> getTopBoard().indexOfFirst { it.first == p.uniqueId }.takeIf { it >= 0 }?.plus(1)?.toString()
                            ?: NO_DATA
                    "times" -> DurationFormatUtils.formatDuration(count.toLong() * 1000, "HH:mm:ss")
                    else -> "UNKNOWN_PARAMS"
                }
            }
        }

        papi.register()
    }

    override fun onDisable() {
        if (this::rankManager.isInitialized) rankManager.savePlayerData().get()
    }


    fun Server.getOffline(str: String): OfflinePlayer? {
        return this.getPlayerUniqueId(str)?.let { this.getOfflinePlayer(it) }
    }


    class Ranker(private val uid: String, private val display: String, private val index: Int) : RankData {
        override fun getId(): String {
            return uid
        }

        override fun getRankDisplay(): String {
            return display
        }

        override fun compareTo(other: RankData?): Int {
            return index.compareTo((other as? Ranker)?.index ?: 0)
        }

    }

    @Prefix(path = "prefix")
    @Resource(locate = "lang.yml")
    class Lang : MessageFile()

    class PlayerDataController(d: Dao<Data, UUID>) : Dao<Data, UUID> by d

    @DataResource(folder = "PlayerData")
    data class Data(@PrimaryKey val uuid: UUID, var scores: Double = 0.0) : DataFile, PlayerData {

        override fun getPlayerUniqueId(): UUID {
            return uuid
        }

        override fun compareTo(other: PlayerData?): Int {
            return other?.score?.compareTo(score) ?: 1
        }

        override fun getScore(): Double {
            return scores
        }

    }

    @Resource(locate = "config.yml")
    data class Config(
            var countDownSeconds: Int,
            val ranks: Map<String, String>,
            val calculator: String,
            val showTop: Int,
            val whenKill: Map<String, Int>) : ConfigFile()

}