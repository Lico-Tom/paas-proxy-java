/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.github.shoothzj.paas.proxy.pulsar.http.controller;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.github.shoothzj.paas.common.module.Semantic;
import com.github.shoothzj.paas.common.proxy.http.module.ProduceMsgReq;
import com.github.shoothzj.paas.common.proxy.http.module.ProduceMsgResp;
import com.github.shoothzj.paas.proxy.pulsar.config.PulsarConfig;
import com.github.shoothzj.paas.proxy.pulsar.module.TopicKey;
import com.github.shoothzj.paas.proxy.pulsar.service.PulsarClientService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.Producer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author hezhangjian
 */
@Slf4j
@RestController
@RequestMapping(path = "/v1/pulsar")
public class ProducerController {

    @Autowired
    private PulsarClientService pulsarClientService;

    @Autowired
    private PulsarConfig pulsarConfig;

    private AsyncLoadingCache<TopicKey, Producer<byte[]>> producerCache;

    private final AtomicInteger atomicInteger = new AtomicInteger();

    @PostConstruct
    public void init() {
        this.producerCache = Caffeine.newBuilder()
                .expireAfterAccess(600, TimeUnit.SECONDS)
                .maximumSize(3000)
                .removalListener((RemovalListener<TopicKey, Producer<byte[]>>) (key, value, cause) -> {
                    log.info("topic {} cache removed, because of {}", key.getTopic(), cause);
                    try {
                        value.close();
                    } catch (Exception e) {
                        log.error("close failed, ", e);
                    }
                })
                .buildAsync(new AsyncCacheLoader<>() {
                    @Override
                    public CompletableFuture<Producer<byte[]>> asyncLoad(TopicKey key, Executor executor) {
                        return acquireFuture(key);
                    }

                    @Override
                    public CompletableFuture<Producer<byte[]>> asyncReload(TopicKey key, Producer<byte[]> oldValue,
                                                                           Executor executor) {
                        return acquireFuture(key);
                    }
                });
    }

    @PostMapping(path = "/tenants/{tenant}/namespaces/{namespace}/topics/{topic}/produce")
    public Mono<ResponseEntity<ProduceMsgResp>> produce(@PathVariable(name = "tenant") String tenant,
                                                        @PathVariable(name = "namespace") String namespace,
                                                        @PathVariable(name = "topic") String topic,
                                                        @RequestBody ProduceMsgReq produceMsgReq) {
        if (StringUtils.isEmpty(produceMsgReq.getMsg())) {
            return Mono.error(new Exception("msg can't be empty"));
        }
        CompletableFuture<ResponseEntity<ProduceMsgResp>> future = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();
        int topicSuffixNum = pulsarConfig.topicSuffixNum;
        if (topicSuffixNum > 0) {
            final int increment = atomicInteger.getAndIncrement();
            int index = increment % topicSuffixNum;
            topic = topic + "_" + index;
        }
        TopicKey topicKey = new TopicKey(tenant, namespace, topic);
        final CompletableFuture<Producer<byte[]>> cacheFuture = producerCache.get(topicKey);
        String finalTopic = topic;
        cacheFuture.whenComplete((producer, e) -> {
            if (e != null) {
                log.error("create pulsar client exception ", e);
                future.complete(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                return;
            }
            try {
                producer.sendAsync(produceMsgReq.getMsg().getBytes(StandardCharsets.UTF_8))
                        .whenComplete(((messageId, throwable) -> {
                            if (throwable != null) {
                                log.error("send producer msg error ", throwable);
                                future.complete(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
                                return;
                            }
                            log.info("topic {} send success, msg id is {}", finalTopic, messageId);
                            if (pulsarConfig.producerSemantic.equals(Semantic.AT_LEAST_ONCE)) {
                                future.complete(new ResponseEntity<>(new ProduceMsgResp(System.currentTimeMillis() - startTime), HttpStatus.OK));
                            }
                        }));
                if (pulsarConfig.producerSemantic.equals(Semantic.AT_MOST_ONCE)) {
                    future.complete(new ResponseEntity<>(new ProduceMsgResp(System.currentTimeMillis() - startTime), HttpStatus.OK));
                }
            } catch (Exception ex) {
                log.error("send async failed ", ex);
                future.complete(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
            }
        });
        return Mono.fromFuture(future);
    }

    private CompletableFuture<Producer<byte[]>> acquireFuture(TopicKey topicKey) {
        CompletableFuture<Producer<byte[]>> future = new CompletableFuture<>();
        try {
            future.complete(pulsarClientService.createProducer(topicKey));
        } catch (Exception e) {
            log.error("create producer exception ", e);
            future.completeExceptionally(e);
        }
        return future;
    }


}
