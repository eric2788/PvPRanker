package com.ericlam.mc.pvpranker

import com.ericlam.mc.kotlib.KotLib
import com.ericlam.mc.kotlib.bukkit.BukkitPlugin
import com.ericlam.mc.kotlib.command.BukkitCommand
import com.ericlam.mc.kotlib.config.Resource
import com.ericlam.mc.kotlib.config.dao.Dao
import com.ericlam.mc.kotlib.config.dao.DataFile
import com.ericlam.mc.kotlib.config.dao.DataResource
import com.ericlam.mc.kotlib.config.dao.PrimaryKey
import com.ericlam.mc.kotlib.config.dto.ConfigFile
import com.ericlam.mc.kotlib.kClassOf
import com.ericlam.mc.kotlib.translateColorCode
import com.ericlam.mc.rankcal.PlayerData
import com.ericlam.mc.rankcal.RankData
import com.ericlam.mc.rankcal.RankDataManager
import com.ericlam.mc.rankcal.implement.RankingLib
import net.md_5.bungee.api.ChatColor
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.scheduler.BukkitRunnable
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PvPRanker : BukkitPlugin() {

    private lateinit var rankManager: RankDataManager

    override fun enable() {
        val manager = KotLib.getConfigFactory(this)
                .register(kClassOf<Config>())
                .registerDao(kClassOf(), kClassOf<PlayerDataController>())
                .dump()
        val controller = manager.getDao(kClassOf<PlayerDataController>())
        val config = manager.getConfig(kClassOf<Config>())
        val api = RankingLib.getRankAPI()
        fun reload(){
            var i = 0
            config.reload()
            val ranks = sortedSetOf(*config.ranks.map { (k,v)-> Ranker(k, v.translateColorCode(), i++) as RankData }.toTypedArray())
            rankManager = api.factory
                    .addPlayers(controller.findAll())
                    .registerRanks(ranks)
                    .registerSaveMechanic {
                        CompletableFuture.runAsync { it.forEach { controller.save { it as Data } } }
                    }
                    .build()
        }

        fun resetRank(){
            rankManager.doCalculate(config.calculator).whenComplete{ map, ex ->
                ex?.printStackTrace()
                info("成功更新 ${map.size} 個數據")
            }
        }

        reload()
        if (config.countDownSeconds > 0){
            var count = config.countDownSeconds
            schedule(async = true, delay = 5, period = 1){
                when{
                    count > 0 -> count--
                    count == 0 -> resetRank().also { count = 0 }
                }
            }
        }
        val command = object : BukkitCommand("ranker", "Ranker Cmmands",
                child = arrayOf(
                        BukkitCommand("reset", "重新結算", "ranker.admin"){ sender, _ ->
                            resetRank()
                            sender.sendMessage("${ChatColor.GREEN} 已重新結算。")
                        },
                        BukkitCommand("see", "查看別人牌位", "ranker.admin",
                                placeholders = arrayOf("player")){ sender, args ->
                            val data = server.getPlayer(args[0])?.let { rankManager.getRankData(it.uniqueId) } ?: let { sender.sendMessage("找不到玩家") ; return@BukkitCommand }
                            sender.sendMessage("${args[0]} 的牌位: ${data.rankDisplay}")
                        },
                        BukkitCommand("reload", "重載插件", "ranker.admin"){
                            sender, _ -> reload().also { sender.sendMessage("重載成功") }
                        }
                )){}

        registerCmd(command)

        listen<PlayerDeathEvent>{
            
        }

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
    data class Config(var countDownSeconds: Int, val ranks: Map<String, String>, val calculator: String) : ConfigFile()

}