package at.rocworks.gateway.core.opcua

import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.TopicValueOpc
import at.rocworks.gateway.core.driver.DriverBase
import at.rocworks.gateway.core.driver.MonitoredItem
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache

import io.vertx.core.AsyncResult
import io.vertx.core.CompositeFuture
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.buffer.Buffer
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

import java.lang.Exception
import java.security.Security
import java.util.function.Predicate

import org.bouncycastle.jce.provider.BouncyCastleProvider

import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.api.UaClient
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfigBuilder
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaMonitoredItem
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscription
import org.eclipse.milo.opcua.sdk.client.api.subscriptions.UaSubscriptionManager.SubscriptionListener
import org.eclipse.milo.opcua.sdk.client.model.nodes.objects.ServerTypeNode
import org.eclipse.milo.opcua.stack.core.*
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.*
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.*
import org.eclipse.milo.opcua.stack.core.types.enumerated.*
import org.eclipse.milo.opcua.stack.core.types.structured.*
import org.eclipse.milo.opcua.stack.core.util.EndpointUtil

import java.io.File
import java.lang.IllegalStateException
import java.lang.NumberFormatException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import kotlin.concurrent.thread

class OpcUaDriver(val config: JsonObject) : DriverBase(config) {
    override fun getType() = Topic.SystemType.Opc

    private val endpointUrl: String = config.getString("EndpointUrl", "")
    private val updateEndpointUrl: Boolean = config.getBoolean("UpdateEndpointUrl", false)

    private val securityPolicy: SecurityPolicy?
    private val identityProvider: IdentityProvider

    private val requestTimeout: Int = config.getInteger("RequestTimeout", 5000)
    private val connectTimeout: Int = config.getInteger("ConnectTimeout", 5000)
    private val keepAliveFailuresAllowed: Int = config.getInteger("KeepAliveFailuresAllowed", 0)
    private val subscriptionSamplingInterval: Double = config.getDouble("SubscriptionSamplingInterval", 0.0)

    private val monitoringParametersBufferSize : UInteger
    private val monitoringParametersBufferSizeDef = 100

    private val monitoringParametersSamplingInterval : Double
    private val monitoringParametersSamplingIntervalDef = 0.0

    private val monitoringParametersDiscardOldest : Boolean
    private val monitoringParametersDiscardOldestDef = false

    private val dataChangeTrigger : DataChangeTrigger
    private val dataChangeTriggerDef = DataChangeTrigger.StatusValueTimestamp

    private val writeParameterQueueSize : Int
    private val writeParameterQueueSizeDef = 1000

    private val writeParametersBlockSize : Int
    private val writeParametersBlockSizeDef = 100

    private val writeParametersWithTime : Boolean
    private val writeParametersWithTimeDef = false

    private val writeSchemaToFile: Boolean = config.getBoolean("WriteSchemaToFile", false)

    private var client: OpcUaClient? = null
    private var subscription: UaSubscription? = null

    private val defaultRetryWaitTime = 5000

    private val schema = JsonObject()

    private var addressNodeIdCache: LoadingCache<String, List<Pair<NodeId, String>>>

    companion object {
        init {
            // Required for SecurityPolicy.Aes256_Sha256_RsaPss
            Security.addProvider(BouncyCastleProvider())
        }
    }

    init {
        val securityPolicyConf = config.getString("SecurityPolicyUri", null)
        securityPolicy = if (securityPolicyConf != null) SecurityPolicy.fromUri(securityPolicyConf) else null
        identityProvider = if (config.containsKey("UsernameProvider")) {
            val value = config.getJsonObject("UsernameProvider") as JsonObject
            UsernameProvider(value.getString("Username"), value.getString("Password"))
        } else AnonymousProvider()
        logger.info("RequestTimeout: [{}] " +
            "ConnectTimeout: [{}] " +
            "KeepAliveFailuresAllowed: [{}] " +
            "SubscriptionSamplingInterval [{}]",
            requestTimeout,
            connectTimeout,
            keepAliveFailuresAllowed,
            subscriptionSamplingInterval
        )

        val monitoringParameters = config.getJsonObject("MonitoringParameters")
        monitoringParametersBufferSize = uint(monitoringParameters?.getInteger("BufferSize", monitoringParametersBufferSizeDef) ?: monitoringParametersBufferSizeDef)
        monitoringParametersSamplingInterval = monitoringParameters?.getDouble("SamplingInterval", monitoringParametersSamplingIntervalDef) ?: monitoringParametersSamplingIntervalDef
        monitoringParametersDiscardOldest = monitoringParameters?.getBoolean("DiscardOldest", monitoringParametersDiscardOldestDef) ?: monitoringParametersDiscardOldestDef
        val dataChangeFilterStr = monitoringParameters?.getString("DataChangeTrigger")
        dataChangeTrigger = if (dataChangeFilterStr == null) dataChangeTriggerDef else {
            DataChangeTrigger.valueOf(dataChangeFilterStr)
        }
        logger.info("MonitoringParameters: "+
                "BufferSize=$monitoringParametersBufferSize " +
                "SamplingInterval=$monitoringParametersSamplingInterval " +
                "DiscardOldest=$monitoringParametersDiscardOldest "+
                "DataChangeTrigger=$dataChangeTrigger")

        val writeParameters = config.getJsonObject("WriteParameters")
        writeParameterQueueSize = writeParameters?.getInteger("QueueSize", writeParameterQueueSizeDef) ?: writeParameterQueueSizeDef
        writeParametersBlockSize = writeParameters?.getInteger("BlockSize", writeParametersBlockSizeDef) ?: writeParametersBlockSizeDef
        writeParametersWithTime = writeParameters?.getBoolean("WithTime", writeParametersWithTimeDef) ?: writeParametersWithTimeDef
        logger.info("WriteParameters: "+
                "QueueSize=$writeParameterQueueSize "+
                "BlockSize=$writeParametersBlockSize "+
                "WithTime=$writeParametersWithTime ")

        val addressCache = config.getJsonObject("AddressCache") ?: JsonObject()
        val maximumSize = addressCache.getLong("MaximumSize", 1000)
        val expireAfterSeconds = addressCache.getLong("ExpireAfterSeconds", 60)

        logger.info("AddressCache: "+
                "MaximumSize=$maximumSize " +
                "ExpireAfterSeconds=$expireAfterSeconds")

        addressNodeIdCache = CacheBuilder.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterAccess(expireAfterSeconds, TimeUnit.SECONDS)
            .build(
                object : CacheLoader<String, List<Pair<NodeId, String>>>() {
                    override fun load(id: String): List<Pair<NodeId, String>> {
                        return browseAddress(id)
                    }
                }
            )

        logger.info(KeyStoreLoader.APPLICATION_URI)
    }

    val writeGetTime = if (writeParametersWithTime) { -> DateTime.nowNanos() } else { -> null }

    private fun endpointFilter(): Predicate<EndpointDescription> {
        return Predicate { e: EndpointDescription ->
            //endpointUpdater(e)
            securityPolicy == null || e.securityPolicyUri == securityPolicy.uri
        }
    }

    private fun endpointUpdater(endpoint: EndpointDescription): EndpointDescription {
        return if (updateEndpointUrl) {
            val parts = endpointUrl.split("://", ":", "/")
            when {
                parts.size == 1 -> {
                    logger.info("Update endpoint to host [{}]!", parts[1])
                    EndpointUtil.updateUrl(endpoint, parts[1])
                }
                parts.size > 1 -> {
                    logger.info("Update endpoint to host [{}] and port [{}]!", parts[1], parts[2])
                    EndpointUtil.updateUrl(endpoint, parts[1], parts[2].toInt())
                }
                else -> {
                    logger.warn("Cannot split endpoint url [{}]", endpoint.endpointUrl)
                    endpoint
                }
            }
        } else {
            endpoint
        }
    }

    private val subscriptionListener: SubscriptionListener = object : SubscriptionListener {
        override fun onKeepAlive(subscription: UaSubscription, publishTime: DateTime) {}
        override fun onStatusChanged(subscription: UaSubscription, status: StatusCode) {
            logger.info("onStatusChanged: $status")
        }

        override fun onPublishFailure(exception: UaException) {
            logger.warn("onPublishFailure: " + exception.message)
        }

        override fun onNotificationDataLost(subscription: UaSubscription) {
            logger.warn("onNotificationDataLost")
        }

        override fun onSubscriptionTransferFailed(subscription: UaSubscription, statusCode: StatusCode) {
            logger.warn("onSubscriptionTransferFailed: $statusCode")
            createSubscription()
        }
    }

    override fun connect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            createClientAsync().onComplete { createResult: AsyncResult<Boolean> ->
                if (createResult.succeeded()) {
                    connectClientAsync().onComplete { connectResult: AsyncResult<Boolean> ->
                        if (connectResult.succeeded()) {
                            logger.info("Connect succeeded")
                            client!!.addFaultListener { serviceFault ->
                                logger.warn("Service Fault: $serviceFault")
                            }

                            client!!.subscriptionManager.addSubscriptionListener(subscriptionListener)
                            createSubscription()

                            promise.complete(true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e.toString())
            promise.fail(e)
        }
        return promise.future()
    }

    private fun browseSchema(nodeId: String): JsonArray {
        logger.info("Start object browsing [{}]", nodeId)
        val tree = browseNode(NodeId.parse(nodeId), maxLevel=-1)
        logger.info("Object browsing finished.")
        if (writeSchemaToFile) {
            File("schema-${id}.json".toLowerCase()).writeText(tree.encodePrettily())
        }
        return tree
    }

    override fun disconnect(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        client!!.disconnect().thenAccept {
            promise.complete(true)
        }
        return promise.future()
    }

    override fun shutdown() {
        disconnect()
    }

    private fun createSubscription() {
        client!!.subscriptionManager
            .createSubscription(subscriptionSamplingInterval)
            .whenCompleteAsync(::createSubscriptionComplete)
    }

    private fun createSubscriptionComplete(s: UaSubscription, e: Throwable?) {
        if (e == null) {
            subscription = s
            resubscribe()
        } else {
            logger.error("Unable to create subscription, reason: " + e.message)
        }
    }

    private fun createClientAsync(): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        createClientThread(ret)
        return ret.future()
    }

    private fun createClientThread(ret: Promise<Boolean>) {
        thread {
            try {
                client = OpcUaClient.create(
                    endpointUrl,
                    { endpoints: List<EndpointDescription> ->
                        endpoints.stream()
                            .filter(endpointFilter())
                            .map { endpoint: EndpointDescription -> endpointUpdater(endpoint) }
                            .findFirst()
                    }
                ) { configBuilder: OpcUaClientConfigBuilder ->
                    configBuilder
                        .setApplicationName(LocalizedText.english(KeyStoreLoader.APPLICATION_NAME))
                        .setApplicationUri(KeyStoreLoader.APPLICATION_URI)
                        .setCertificate(KeyStoreLoader.keyStoreLoader.clientCertificate)
                        .setKeyPair(KeyStoreLoader.keyStoreLoader.clientKeyPair)
                        .setIdentityProvider(identityProvider)
                        .setRequestTimeout(uint((requestTimeout)))
                        .setConnectTimeout(uint((connectTimeout)))
                        .setKeepAliveFailuresAllowed(uint((keepAliveFailuresAllowed)))
                        .build()
                }
                logger.info("OpcUaClient created.")
                ret.complete(true)
            } catch (e: UaException) {
                logger.info("OpcUaClient create failed! Wait and retry... " + e.message)
                vertx.setTimer(defaultRetryWaitTime.toLong()) { createClientThread(ret) }
            } catch (e: Exception) {
                ret.fail(e)
            }
        }
    }

    private fun connectClientAsync(): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        connectClient(promise)
        return promise.future()
    }

    private fun connectClient(promise: Promise<Boolean>) {
        if (client == null) {
            promise.fail("ConnectClientAsync where client==null!")
        } else {
            client!!.connect().whenCompleteAsync { _: UaClient?, e: Throwable? ->
                if (e == null) {
                    logger.info("OpcUaClient connected [{}] [{}]", id, endpointUrl)
                    promise.complete(true)
                } else {
                    logger.info("OpcUaClient connect failed! Wait and retry... " + e.message)
                    vertx.setTimer(defaultRetryWaitTime.toLong()) { connectClient(promise) }
                }
            }
        }
    }

    private fun rdToNodeId(rd: ReferenceDescription): NodeId {
        return rd.nodeId.toNodeId(client!!.namespaceTable).get()
    }

    override fun browseHandler(message: Message<JsonObject>) {
        try {
            val startNodeId = NodeId.parseOrNull(message.body().getString("NodeId", ""))
            val reverse = message.body().getBoolean("Reverse", false)
            val maxLevel = if (reverse) -1 else 1
            if (startNodeId != null) {
                val result = browseNode(startNodeId, reverse = reverse, maxLevel = maxLevel)
                message.reply(JsonObject().put("Ok", true).put("Result", result))
            } else {
                message.reply(JsonObject().put("Ok", false).put("Result", null))
            }
        } catch (e: Exception) {
            message.fail(-1, e.message)
            e.printStackTrace()
        }
    }

    override fun schemaHandler(message: Message<JsonObject>) {
        val body = message.body()
        val nodeIds = if (body.containsKey("NodeId")) listOf(body.getString("NodeId"))
        else body.getJsonArray("NodeIds") ?: JsonArray(listOf("i=85"))
        thread {
            nodeIds.filterIsInstance<String>().forEach { nodeId ->
                logger.info("Browse from NodeId [{}]", nodeId)
                schema.put(nodeId, browseSchema(nodeId))
            }
            message.reply(schema)
        }
    }

    override fun subscribeTopics(topics: List<Topic>): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        CompositeFuture.all(
            subscribeNodes(topics.filter { it.topicType === Topic.TopicType.NodeId }),
            subscribePath(topics.filter { it.topicType === Topic.TopicType.Path })
        ).onComplete { promise.complete(it.succeeded()) }
        return promise.future()
    }

    private fun getVariantOfValue(value: Buffer, nodeId: NodeId): Variant {
        return if (value.length() == 0)
            Variant.NULL_VALUE
        else
            getVariantOfValue(value.toString(), nodeId)
    }

    private fun getVariantOfValue(value: String, nodeId: NodeId): Variant {
        try {
            return when (val type = client!!.addressSpace.getVariableNode(nodeId).dataType.identifier) {
                Identifiers.String.identifier -> Variant(value)
                Identifiers.Float.identifier -> Variant(value.toFloat())
                Identifiers.Double.identifier -> Variant(value.toDouble())

                Identifiers.Int16.identifier -> Variant(value.toShort())
                Identifiers.Int32.identifier -> Variant(value.toInt())
                Identifiers.Integer.identifier -> Variant(value.toInt())

                Identifiers.UInt16.identifier -> Variant(ushort(value.toShort()))
                Identifiers.UInt32.identifier -> Variant(uint(value.toInt()))
                Identifiers.UInteger.identifier -> Variant(uint(value.toInt()))

                Identifiers.Int64.identifier -> Variant(value.toLong())
                Identifiers.UInt64.identifier -> Variant(ulong(value.toLong()))

                Identifiers.SByte.identifier,
                Identifiers.Byte.identifier -> Variant(
                    ubyte(value.toByteArray(StandardCharsets.UTF_8)[0])
                )
                Identifiers.Boolean.identifier -> Variant(
                    !(value == "0" || value.equals("false", ignoreCase = true))
                )
                else -> {
                    logger.warn("Unhandled data type $type")
                    Variant.NULL_VALUE
                }
            }
        } catch (e: Exception) {
            logger.warn("Converting value to variant exception [{}] [{}] [{}]", nodeId, value, e.message)
            return Variant.NULL_VALUE
        }
    }

    override fun publishTopic(topic: Topic, value: Buffer): Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            fun dataValue(nodeId: NodeId) =
                when (topic.format) {
                    Topic.Format.Value ->
                        DataValue(getVariantOfValue(value, nodeId), null, writeGetTime())
                    Topic.Format.Json,
                    Topic.Format.Pretty -> {
                        logger.warn("Value format not yet implemented!") // TODO
                        DataValue(Variant.NULL_VALUE, null, null)
                    }
                }

            when (topic.topicType) {
                Topic.TopicType.NodeId -> {
                    val nodeId = NodeId.parse(topic.address)
                    writeValueQueued(nodeId, dataValue(nodeId)).onComplete(ret)
                }
                Topic.TopicType.Path -> {
                    addressNodeIdCache.get(topic.address).forEach {
                        writeValueQueued(it.first, dataValue(it.first)).onComplete(ret)
                    }
                }
                else -> {
                    logger.warn("Item type [{}] not yet implemented!", topic.topicType)
                    ret.complete(false)
                }
            }
        } catch (e: NumberFormatException) {
            logger.warn("Not a valid number [{}] for numeric tag [{}] value!", value.toString(), topic)
            ret.complete(false)
        } catch (e: Exception) {
            ret.fail(e)
        }
        return ret.future()
    }

    private val writeValueQueue = ArrayBlockingQueue<Triple<NodeId, DataValue, Promise<Boolean>>>(writeParameterQueueSize)

    private val writeValueThread =
        thread {
            while (true) {
                val nodeIds = ArrayList<NodeId>(writeParametersBlockSize)
                val dataValues = ArrayList<DataValue>(writeParametersBlockSize)
                val promises = ArrayList<Promise<Boolean>>(writeParametersBlockSize)
                fun addIt(it : Triple<NodeId, DataValue, Promise<Boolean>>) : Boolean {
                    nodeIds.add(it.first)
                    dataValues.add(it.second)
                    promises.add(it.third)
                    return true
                }
                var got = writeValueQueue.poll(1, TimeUnit.SECONDS)?.let(::addIt) ?: false
                while (got && nodeIds.size < writeParametersBlockSize) {
                    got = writeValueQueue.poll()?.let(::addIt) ?: false
                }
                if (nodeIds.size > 0) {
                    try {
                        val results = client!!.writeValues(nodeIds, dataValues).get()
                        results.zip(promises).forEach {
                            if (!it.first.isGood) logger.warn("Writing value was not good [{}]", it.first.toString())
                            it.second.complete(it.first.isGood)
                        }
                    } catch (e: Exception) {
                        logger.warn("Write value threw exception [{}]", e.message)
                    }
                }
            }
        }

    private fun writeValueAsync(nodeId: NodeId, dataValue: DataValue): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        client!!.writeValue(nodeId, dataValue).thenAccept { status: StatusCode ->
            if (status.isGood) {
                logger.debug("Wrote [{}] to nodeId=[{}]", dataValue.value.toString(), nodeId)
                promise.complete(true)
            } else {
                logger.warn(
                    "Wrote [{}] to nodeId=[{}] with status {}",
                    dataValue.value.toString(),
                    nodeId,
                    status
                )
                promise.complete(false)
            }
        }
        return promise.future()
    }

    private var lastWriteFailures = 0
    private fun writeValueQueued(nodeId: NodeId, dataValue: DataValue): Future<Boolean> {
        val promise = Promise.promise<Boolean>()
        try {
            writeValueQueue.add(Triple(nodeId, dataValue, promise))
            if (lastWriteFailures > 0) {
                logger.error("Add to write queue: Ok [{} missed writes]", lastWriteFailures)
                lastWriteFailures = 0
            }
        } catch (e: IllegalStateException) {
            if (lastWriteFailures == 0) {
                logger.error("Add to write queue: ${e.message}")
            }
            lastWriteFailures++
            promise.complete(false)
        }
        return promise.future()
    }

    override fun readServerInfo(): JsonObject {
        val result = JsonObject()
        val serverNode = client!!.addressSpace.getObjectNode(
            Identifiers.Server,
            Identifiers.ServerType
        ) as ServerTypeNode

        // Read properties of the Server object...
        val server = JsonArray()
        val namespace = JsonArray()
        result.put("Server", server)
        result.put("Namespace", namespace)
        serverNode.serverArray.forEach { server.add(it) }
        Arrays.stream(serverNode.namespaceArray).forEach { namespace.add(it) }
        val serverStatusNode = serverNode.serverStatusNode
        result.put("BuildInfo", serverStatusNode.buildInfo.toString())
        result.put("StartTime", serverStatusNode.startTime.javaInstant.toString())
        result.put("CurrentTime", serverStatusNode.currentTime.javaInstant.toString())
        result.put("ServerStatus", serverStatusNode.state.toString())
        return result
    }

    override fun readHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")
        when {
            node != null && node is String -> {
                val nodeId = NodeId.parse(node)
                client!!.readValue(0.0, TimestampsToReturn.Both, nodeId).thenAccept { value ->
                    val result = TopicValueOpc.fromDataValue(value).encodeToJson()
                    message.reply(JsonObject().put("Ok", true).put("Result", result))
                }
            }
            node != null && node is JsonArray -> {
                val nodeIds = node.mapNotNull { if (it is String) NodeId.parse(it) else null }
                client!!.readValues(0.0, TimestampsToReturn.Both, nodeIds).thenAccept { list ->
                    val result = JsonArray()
                    list.forEach {
                        result.add(TopicValueOpc.fromDataValue(it).encodeToJson())
                    }
                    message.reply(JsonObject().put("Ok", true).put("Result", result))
                }
            }
            else -> {
                val err = String.format("Invalid format in read request!")
                message.reply(JsonObject().put("Ok", false))
                logger.error(err)
            }
        }
    }

    override fun writeHandler(message: Message<JsonObject>) {
        val node = message.body().getValue("NodeId")
        when {
            node != null && node is String -> {
                val nodeId = NodeId.parse(node)
                val value = message.body().getString("Value", "")
                val dataValue = DataValue(getVariantOfValue(value, nodeId), null, writeGetTime())
                writeValueQueued(nodeId, dataValue).onComplete {
                    message.reply(JsonObject().put("Ok", it.succeeded() && it.result()))
                }
            }
            node != null && node is JsonArray -> {
                val values = message.body().getJsonArray("Value", JsonArray())
                CompositeFuture.all(node.zip(values).mapNotNull {
                    if (it.first is String && it.second is String) {
                        val nodeId = NodeId.parseSafe(it.first as String)
                        if (nodeId.isPresent) {
                            val variant = getVariantOfValue(it.second as String, nodeId.get())
                            val dataValue = DataValue(variant, null, writeGetTime())
                            writeValueQueued(nodeId.get(), dataValue) // TODO: optimize and replace with client.writeValues(nodeIds, dataValues)
                        } else null
                    } else null
                }).onComplete { result ->
                    val results = result.result().list<Boolean>()
                    message.reply(JsonObject().put("Ok", JsonArray(results)))
                }
            }
            else -> {
                val err = String.format("Invalid format in write request!")
                message.reply(JsonObject().put("Ok", false))
                logger.error(err)
            }
        }
    }

    private fun subscribeNodes(topics: List<Topic>) : Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        if (topics.isEmpty()) ret.complete(true)
        else {
            logger.info("Subscribe nodes [{}] sampling interval [{}]", topics.size, monitoringParametersSamplingInterval)
            val nodeIds = topics.map { NodeId.parseOrNull(it.address) }.toList()
            val requests = ArrayList<MonitoredItemCreateRequest>()

            val dataChangeFilter = ExtensionObject.encode(client!!.serializationContext, DataChangeFilter(
                dataChangeTrigger,
                uint(DeadbandType.None.value),
                0.0
            ));

            nodeIds.forEach { nodeId ->
                val clientHandle = subscription!!.nextClientHandle()
                requests.add(
                    MonitoredItemCreateRequest(
                        ReadValueId(nodeId, AttributeId.Value.uid(),null, QualifiedName.NULL_VALUE),
                        MonitoringMode.Reporting,
                        MonitoringParameters(
                            clientHandle,
                            monitoringParametersSamplingInterval,
                            dataChangeFilter,
                            monitoringParametersBufferSize,
                            monitoringParametersDiscardOldest
                        )
                    )
                )
            }

            // when creating items in MonitoringMode.Reporting this callback is where each item needs to have its
            // value/event consumer hooked up. The alternative is to create the item in sampling mode, hook up the
            // consumer after the creation call completes, and then change the mode for all items to reporting.
            val onItemCreated =
                BiConsumer { item: UaMonitoredItem, nr: Int ->
                    val topic = topics[nr]
                    if (item.statusCode.isGood)
                        registry.addMonitoredItem(OpcUaMonitoredItem(item), topic)
                    item.setValueConsumer { data: DataValue ->
                        //println("callback: id="+ item.monitoredItemId+ " : size=" +topics.size + " : "+ item.clientHandle.toInt() + " : " + item.readValueId.nodeId.toParseableString() + " : " + data.value.toString())
                        valueConsumer(topic, data)
                    }
                }

            subscription!!
                .createMonitoredItems(TimestampsToReturn.Both, requests, onItemCreated)
                .thenAccept { monitoredItems: List<UaMonitoredItem> ->
                    try {
                        for (item in monitoredItems) {
                            if (item.statusCode.isGood) {
                                logger.debug("Monitored item created for nodeId {}", item.readValueId.nodeId)
                            } else {
                                logger.warn(
                                    "Failed to create item for nodeId {} (status={})",
                                    item.readValueId.nodeId,
                                    item.statusCode
                                )
                            }
                        }
                        ret.complete(true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        ret.fail(e)
                    }
                }
        }
        return ret.future()
    }

    private fun subscribePath(topics: List<Topic>) : Future<Boolean> {
        return vertx.executeBlocking { ret ->
            if (topics.isEmpty()) ret.complete(true)
            else
            try {
                val resolvedTopics = mutableListOf<Topic>()
                topics.forEach { topic ->
                    logger.info("Subscribe path [{}]", topic)
                    val resolvedNodeIds = addressNodeIdCache.get(topic.address)
                    resolvedTopics.addAll(resolvedNodeIds.map {
                        Topic(
                            topicName = topic.topicName,
                            systemType = topic.systemType,
                            topicType = topic.topicType,
                            systemName = topic.systemName,
                            address = it.first.toParseableString(),
                            format = topic.format,
                            browsePath = it.second
                        )
                    })
                }
                logger.info("Browse path result size [{}]", resolvedTopics.size)
                if (topics.isEmpty()) {
                    ret.complete(true)
                } else if (resolvedTopics.size>0) {
                    subscribeNodes(resolvedTopics).onComplete(ret)
                } else {
                    ret.complete(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ret.fail(e)
            }
        }
    }

    private fun getRootNodeIdOfName(item: String) = when (item) {
        "Root" -> "i=84"
        "Objects" -> "i=85"
        "Types" -> "i=86"
        "Views" -> "i=87"
        else -> item
    }

    override fun unsubscribeTopics(topics: List<Topic>, items: List<MonitoredItem>) : Future<Boolean> {
        val ret = Promise.promise<Boolean>()
        try {
            val opcUaItems = items.map { (it as OpcUaMonitoredItem).item }
            logger.debug("Unsubscribe items [{}]", opcUaItems.joinToString(",") { it.readValueId.nodeId.toString() })
            if (items.isNotEmpty()) {
                subscription!!.deleteMonitoredItems(opcUaItems)
            }
            ret.complete(true)
        } catch (e: Exception) {
            e.printStackTrace()
            ret.fail(e)
        }
        return ret.future()
    }


    private fun valueConsumer(topic: Topic, data: DataValue) {
        logger.debug("Got value [{}] [{}]", topic.topicName, data.value.toString())
        try {
            val value = TopicValueOpc.fromDataValue(data)
            fun json() = JsonObject()
                .put("Topic", topic.encodeToJson())
                .put("Value", value.encodeToJson())

            val buffer : Buffer? = when (topic.format) {
                Topic.Format.Value -> {
                    data.value?.value?.let {
                        Buffer.buffer(it.toString())
                    }
                }
                Topic.Format.Json -> Buffer.buffer(json().encode())
                Topic.Format.Pretty -> Buffer.buffer(json().encodePrettily())
            }
            if (buffer!=null) {
                vertx.eventBus().publish(topic.topicName, buffer)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun browseNode(
        nodeId: NodeId,
        maxLevel: Int=1,
        flat: Boolean=false,
        reverse: Boolean=false
    ): JsonArray {
        val tStart = Instant.now()
        var tLast = tStart
        var counter = 0

        fun browse(startNodeId: NodeId, maxLevel: Int, level: Int, flat: Boolean, path: String=""): JsonArray {
            val result = JsonArray()

            fun addResult(references: List<ReferenceDescription>) {
                references.filter {
                    it.referenceTypeId == BuiltinReferenceType.Organizes.nodeId ||
                    it.referenceTypeId == BuiltinReferenceType.HasComponent.nodeId ||
                    it.referenceTypeId == BuiltinReferenceType.HasProperty.nodeId
                }.forEach { rd ->
                    counter++
                    if (counter % 1000 == 0) { // It's faster not do get the current time with every item
                        val tNow = Instant.now()
                        if (Duration.between(tLast, tNow).seconds > 1 ) {
                            tLast = tNow
                            logger.info("Browsed [{}] items...", counter)
                        }
                    }
                    val item = JsonObject()
                    item.put("BrowseName", rd.browseName.name)
                    item.put("BrowsePath", path+rd.browseName.name)
                    item.put("DisplayName", rd.displayName.text)
                    item.put("NodeId", rd.nodeId.toParseableString())
                    item.put("NodeClass", rd.nodeClass.toString())

                    if (rd.nodeClass === NodeClass.Variable || !flat) result.add(item)

                    // recursively browse to children if it is an object node
                    if ((maxLevel == -1 || level < maxLevel) && rd.nodeClass === NodeClass.Object) {
                        val rdNodeId = rd.nodeId.toNodeId(client!!.namespaceTable)
                        if (rdNodeId.isPresent) {
                            val next = browse(rdNodeId.get(), maxLevel, level + 1, flat, path+rd.browseName.name+"/")
                            if (flat) {
                                result.addAll(next)
                            } else {
                                item.put("Nodes", next)
                            }
                        }
                    }
                }
            }

            val browse = BrowseDescription(
                startNodeId,
                if (reverse) BrowseDirection.Inverse else BrowseDirection.Forward,
                Identifiers.References,
                true,
                uint(NodeClass.Object.value or NodeClass.Variable.value),
                uint(BrowseResultMask.All.value)
            )

            try {
                val browseResult = client!!.browse(browse).get()
                if (browseResult.statusCode.isGood && browseResult.references != null) {
                    addResult(browseResult.references.asList())
                    var continuationPoint = browseResult.continuationPoint
                    while (continuationPoint != null && continuationPoint.isNotNull) {
                        val nextResult = client!!.browseNext(false, continuationPoint).get()
                        addResult(nextResult.references.asList())
                        continuationPoint = nextResult.continuationPoint
                    }
                } else {
                    logger.error("Browsing nodeId [{}] failed [{}]", startNodeId, browseResult.statusCode.toString())
                }
            } catch (e: InterruptedException) {
                logger.error("Browsing nodeId [{}] exception: [{}]", startNodeId, e.message)
            } catch (e: ExecutionException) {
                logger.error("Browsing nodeId [{}] exception: [{}]", startNodeId, e.message)
            }

            return result
        }

        val result = browse(nodeId, maxLevel, 1, flat)
        val duration = Duration.between(tStart, Instant.now())
        val seconds = duration.seconds + duration.nano/1_000_000_000.0
        if (seconds > 1.0) {
            logger.info(
                "Browsed [{}] items in [{}] seconds [{}] items/s.",
                counter,
                seconds,
                if (seconds>0) counter / seconds else 0
            )
        }

        return result
    }

    private fun browseAddress(address: String): List<Pair<NodeId, String>> {
        val resolvedNodeIds = mutableListOf<Pair<NodeId, String>>()
        val items = Topic.splitAddress(address)
        fun find(node: String, itemIdx: Int, path: String) {
            val item = items[itemIdx]
            val nodeId = NodeId.parseOrNull(node)
            if (nodeId != null) {
                val result = browseNode(nodeId)
                    .filterIsInstance<JsonObject>()
                    .filter { item == "#" || item == "+" || item == it.getString("BrowseName", "") }
                val nextIdx = if (item != "#" && itemIdx + 1 < items.size) itemIdx + 1 else itemIdx
                result.forEach {
                    val childNodeId = NodeId.parseOrNull(it.getString("NodeId"))
                    val browsePath = path+"/"+it.getString("BrowseName")
                    if (childNodeId != null) when (it.getString("NodeClass")) {
                        "Variable" -> resolvedNodeIds.add(Pair(childNodeId, browsePath))
                        "Object" -> find(it.getString("NodeId", ""), nextIdx, browsePath)
                    }
                }
            }
        }
        val tStart = Instant.now()
        val start = getRootNodeIdOfName(items.first())
        find(start, 1, items.first())
        val duration = Duration.between(tStart, Instant.now())
        val seconds = duration.seconds + duration.nano/1_000_000_000.0
        if (seconds > 0.100)
            logger.warn("Browsing address [{}] took long time [{}]s", address, seconds)
        return resolvedNodeIds
    }
}