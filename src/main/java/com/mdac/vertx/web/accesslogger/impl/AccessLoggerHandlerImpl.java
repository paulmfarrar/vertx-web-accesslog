/*
 * Copyright (c) 2016-2019 Roman Pierson
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License v2.0 
 * which accompanies this distribution.
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */
package com.mdac.vertx.web.accesslogger.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

import com.mdac.vertx.web.accesslogger.AccessLoggerConstants;
import com.mdac.vertx.web.accesslogger.AccessLoggerConstants.HandlerConfiguration;
import com.mdac.vertx.web.accesslogger.AccessLoggerConstants.Messages.RawEvent;
import com.mdac.vertx.web.accesslogger.AccessLoggerConstants.Messages.Registration;
import com.mdac.vertx.web.accesslogger.AccessLoggerConstants.Request.Data;
import com.mdac.vertx.web.accesslogger.AccessLoggerHandler;
import com.mdac.vertx.web.accesslogger.verticle.AccessLoggerProducerVerticle;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;

/**
 * 
 * Access Logger for requests
 * 
 * @author Roman Pierson
 *
 */
public class AccessLoggerHandlerImpl implements AccessLoggerHandler {

	private final Logger LOG = LoggerFactory.getLogger(this.getClass().getName());
	
	private final EventBus eventBus;
	
	private Collection<String> registeredIdentifiers = new ArrayList<>();
	private boolean allConfigurationsSuccessfullyRegistered = false;
	private final int requiredConfigurationsCounter;
	
	private boolean requiresIncomingHeaders;
	private boolean requiresOutgoingHeaders;
	private boolean requiresCookies;
	
	private static final Object lock = new Object();
	private static boolean isProducerVerticleCreated = false;
	
	public AccessLoggerHandlerImpl(final JsonObject handlerConfiguration) {
		
		if(handlerConfiguration == null || handlerConfiguration.getJsonArray(HandlerConfiguration.CONFIG_KEY_CONFIGURATIONS, new JsonArray()).size() <= 0){
			throw new IllegalArgumentException("must specify at least one valid configuration");
		}
		
		this.requiredConfigurationsCounter = handlerConfiguration.getJsonArray(HandlerConfiguration.CONFIG_KEY_CONFIGURATIONS).size();
		
		eventBus = Vertx.currentContext().owner().eventBus();
		
		if(handlerConfiguration.getBoolean(HandlerConfiguration.CONFIG_KEY_IS_AUTO_DEPLOY_PRODUCER_VERTICLE, true)) {
			
			synchronized(lock) {
				if(!isProducerVerticleCreated) {
					
					LOG.info("Start creating singleton verticle");
					
					Vertx.currentContext().owner().deployVerticle(AccessLoggerProducerVerticle.class.getName(), new DeploymentOptions().setWorker(true));
					
					isProducerVerticleCreated = true;
						
				}
			}
		} 
		
		handlerConfiguration.getJsonArray(HandlerConfiguration.CONFIG_KEY_CONFIGURATIONS).forEach(xConfiguration -> {
			
			if(!(xConfiguration instanceof JsonObject)) {
				throw new IllegalArgumentException("must specify a valid configuration");
			}
			
			final JsonObject configuration = (JsonObject) xConfiguration;
			
			eventBus.<JsonObject>send(AccessLoggerConstants.EVENTBUS_REGISTER_EVENT_NAME, configuration, ar -> {
				
				final String configurationIdentifier = configuration.getString(Registration.Request.IDENTIFIER);
				
				if(ar.succeeded()) {
					JsonObject response = ar.result().body();
					if(Registration.Response.RESULT_OK.equals(response.getString(Registration.Response.RESULT, null))){
						
						this.requiresCookies = response.getBoolean(Registration.Response.REQUIRES_COOKIES, false) ? true : this.requiresCookies;
						this.requiresIncomingHeaders = response.getBoolean(Registration.Response.REQUIRES_INCOMING_HEADERS, false) ? true : this.requiresIncomingHeaders;
						this.requiresOutgoingHeaders = response.getBoolean(Registration.Response.REQUIRES_OUTGOING_HEADERS, false) ? true : this.requiresOutgoingHeaders;
						
						this.registeredIdentifiers.add(configurationIdentifier);
						
						if(this.requiredConfigurationsCounter == this.registeredIdentifiers.size()) {
							this.allConfigurationsSuccessfullyRegistered = true;
							LOG.debug("Successfully registered all [{}] configurations with identifiers {}", this.requiredConfigurationsCounter, this.registeredIdentifiers);
							if(this.requiresCookies || this.requiresIncomingHeaders || this.requiresOutgoingHeaders) {
								LOG.debug("Specific data required for cookies [{}], incoming headers [{}], outgoing headers [{}]", this.requiresCookies, this.requiresIncomingHeaders, this.requiresOutgoingHeaders);
							} else {
								LOG.debug("No specific data required");
							}
						}
						
					} else {
						throw new RuntimeException("Unable to register access log configuration for identifier [" + configurationIdentifier + "]");
					}
					
				} else {
					throw new RuntimeException("Unable to register access log configuration [" + configurationIdentifier + "]", ar.cause());
				}
			});
		});
		
	}
	
	@Override
	public void handle(final RoutingContext context) {
		
		if(!allConfigurationsSuccessfullyRegistered) {
			LOG.error("Handler not ready to log due to missing registration(s)");
			context.next();
		}
		
		long startTSmillis = System.currentTimeMillis();
		
		context.addBodyEndHandler(v -> log(context, startTSmillis));
		
		context.next();
		
	}
	
	private void log(final RoutingContext context, long startTSmillis){
		
		final HttpServerRequest request = context.request();
		final HttpServerResponse response = context.response();
		
		JsonObject jsonValues = new JsonObject()
										.put(RawEvent.Request.IDENTIFIERS, this.registeredIdentifiers)
										.put(Data.Type.START_TS_MILLIS.getFieldName(), startTSmillis)
										.put(Data.Type.END_TS_MILLIS.getFieldName(), System.currentTimeMillis())
										.put(Data.Type.STATUS.getFieldName(), response.getStatusCode())
										.put(Data.Type.METHOD.getFieldName(), request.method().name())
										.put(Data.Type.URI.getFieldName(), request.path())
										.put(Data.Type.VERSION.getFieldName(), request.version())
										.put(Data.Type.REMOTE_HOST.getFieldName(), request.remoteAddress().host())
										.put(Data.Type.LOCAL_HOST.getFieldName(), request.host().contains(":") ? request.host().substring(0, request.host().indexOf(":")): request.host())
										.put(Data.Type.LOCAL_PORT.getFieldName(), request.localAddress().port());
		
		if(request.query() != null && !request.query().trim().isEmpty()){
			jsonValues.put(Data.Type.QUERY.getFieldName(), request.query());
		}
		
		if(response.bytesWritten() > 0){
			jsonValues.put(Data.Type.BYTES_SENT.getFieldName(), response.bytesWritten());
		}
		
		if(requiresIncomingHeaders) {
			jsonValues.put(Data.Type.REQUEST_HEADERS.getFieldName(), extractHeaders(request.headers()));
		}
		
		if(requiresOutgoingHeaders) {
			jsonValues.put(Data.Type.RESPONSE_HEADERS.getFieldName(), extractHeaders(response.headers()));
		}
		
		if(requiresCookies) {
			jsonValues.put(Data.Type.COOKIES.getFieldName(), extractCookies(context.cookies()));
		}
		
		eventBus.send(AccessLoggerConstants.EVENTBUS_RAW_EVENT_NAME, jsonValues);
		
	}
	
	private JsonObject extractHeaders(final MultiMap headersMap){
		
		JsonObject headers = new JsonObject();
		headersMap.forEach(entry -> {
			headers.put(entry.getKey(), entry.getValue());
		});
		
		return headers;
		
	}
	
	private JsonArray extractCookies(final Set<Cookie> cookies) {
		
		JsonArray jsonArCookies = new JsonArray();
		
		for(final Cookie cookie : cookies) {
			jsonArCookies.add(new JsonObject().put(Data.Fields.COOKIE_NAME, cookie.getName()).put(Data.Fields.COOKIE_VALUE, cookie.getValue()));
		}
		
		return jsonArCookies;
		
	}
}
