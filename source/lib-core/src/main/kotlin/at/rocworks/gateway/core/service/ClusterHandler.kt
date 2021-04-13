package at.rocworks.gateway.core.service

import at.rocworks.gateway.core.cache.OpcNode
import at.rocworks.gateway.core.cache.OpcValue
import at.rocworks.gateway.core.data.*
import at.rocworks.gateway.core.data.CodecTopic
import at.rocworks.gateway.core.data.CodecTopicValueOpc

import org.slf4j.LoggerFactory

import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject

import java.io.FileNotFoundException
import java.util.logging.LogManager

import kotlin.concurrent.thread
import kotlin.system.exitProcess
import io.vertx.core.spi.cluster.ClusterManager
import io.vertx.core.spi.cluster.NodeListener
import java.io.File
import java.net.URL
import org.slf4j.Logger

import io.vertx.spi.cluster.ignite.IgniteClusterManager
import io.vertx.spi.cluster.ignite.impl.VertxLogger
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager

import org.apache.ignite.events.EventType
import org.apache.ignite.configuration.IgniteConfiguration

import com.hazelcast.config.FileSystemYamlConfig
import io.vertx.core.eventbus.EventBusOptions
import java.net.InetAddress




object ClusterHandler {
    private val logger: Logger = LoggerFactory.getLogger(javaClass.simpleName)

    private val clusterType = (System.getenv("GATEWAY_CLUSTER_TYPE") ?: "ignite").toLowerCase()
    private val hostAddress = System.getenv("GATEWAY_BUS_HOST") ?: ""
    private val portAddress = System.getenv("GATEWAY_BUS_PORT") ?: ""

    private val clusterManager: ClusterManager = when (clusterType) {
        "hazelcast" -> getHazelcastClusterManager()
        "ignite" -> getIgniteClusterManager()
        else -> throw IllegalArgumentException("Unknown cluster type '$clusterType'")
    }

    fun init(args: Array<String>, services: (Vertx, JsonObject) -> Unit) {
        val stream = ClusterHandler::class.java.classLoader.getResourceAsStream("logging.properties")
        try {
            LogManager.getLogManager().readConfiguration(stream)
        } catch (e: Exception) {
            println("Error loading logging.properties!")
            exitProcess(-1)
        }

        val eventBusOptions = EventBusOptions()
        logger.info("HostAddress [{}] [{}]", hostAddress, portAddress)
        (if (hostAddress=="") InetAddress.getLocalHost().hostAddress else hostAddress).let {
            eventBusOptions.host = it
            eventBusOptions.clusterPublicHost = it
        }

        if (portAddress!="") {
            eventBusOptions.port = portAddress.toInt()
            eventBusOptions.clusterPublicPort = portAddress.toInt()
        }

        val vertxOptions = VertxOptions()
            .setEventBusOptions(eventBusOptions)
            .setClusterManager(clusterManager)

        Vertx.clusteredVertx(vertxOptions).onComplete { vertx ->
            if (vertx.succeeded()) {
                initCluster(args, vertx.result(), services)
                ClusterCache.init(clusterManager, vertx.result())
            } else {
                logger.error("Error initializing cluster!")
            }
        }
    }

    fun getNodeId(): String {
        return clusterManager.nodeId
    }

    private fun initCluster(args: Array<String>, vertx: Vertx, services: (Vertx, JsonObject) -> Unit) {
        logger.info("Cluster nodeId: ${clusterManager.nodeId}")

        val configFilePath = if (args.isNotEmpty()) args[0] else System.getenv("config") ?: "config.yaml"
        logger.info("Gateway config file: $configFilePath")

        val serviceHandler = ServiceHandler(vertx, logger)

        val nodeListener = object : NodeListener {
            override fun nodeAdded(nodeID: String) {
                logger.info("Added nodeId: $nodeID")
            }

            override fun nodeLeft(nodeID: String) {
                logger.info("Removed nodeId: $nodeID")
                serviceHandler.removeClusterNode(nodeID)
            }
        }
        clusterManager.nodeListener(nodeListener)

        try {
            // Register Message Types
            vertx.eventBus().registerDefaultCodec(Topic::class.java, CodecTopic())
            vertx.eventBus().registerDefaultCodec(TopicValueOpc::class.java, CodecTopicValueOpc())
            vertx.eventBus().registerDefaultCodec(TopicValuePlc::class.java, CodecTopicValuePlc())
            vertx.eventBus().registerDefaultCodec(TopicValueDds::class.java, CodecTopicValueDds())

            // Retrieve Config
            val config = Globals.retrieveConfig(vertx, configFilePath)

            // Go through the configuration file
            config.getConfig { cfg ->
                if (cfg == null || cfg.failed()) {
                    println("Missing or invalid $configFilePath file!")
                    config.close()
                    vertx.close()
                } else {
                    thread { // because it will block
                        services(vertx, cfg.result())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getIgniteClusterManager() = try {
        val fileName = File("ignite.xml")
        val clusterConfig = URL(fileName.readText())
        logger.info("Cluster config file [{}]", fileName)
        IgniteClusterManager(clusterConfig)
    } catch (e: FileNotFoundException) {
        logger.info("Cluster default configuration.")
        val config = IgniteConfiguration()
        config.gridLogger = VertxLogger()
        config.metricsLogFrequency = 0
        config.setIncludeEventTypes(
            EventType.EVT_CACHE_OBJECT_PUT,
            EventType.EVT_CACHE_OBJECT_READ,
            EventType.EVT_CACHE_OBJECT_REMOVED,
            EventType.EVT_NODE_JOINED,
            EventType.EVT_NODE_LEFT,
            EventType.EVT_NODE_FAILED
        )
        IgniteClusterManager(config)
    }

    private fun getHazelcastClusterManager() = try {
        val fileName = "hazelcast.yaml"
        val clusterConfig = FileSystemYamlConfig(fileName)
        logger.info("Cluster config file [{}]", fileName)
        HazelcastClusterManager(clusterConfig)
    } catch (e: FileNotFoundException) {
        logger.info("Cluster default configuration.")
        HazelcastClusterManager()
    }
}
