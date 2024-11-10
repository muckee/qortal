package org.qortal.api.websocket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.api.ApiError;
import org.qortal.controller.Controller;
import org.qortal.data.arbitrary.DataMonitorInfo;
import org.qortal.event.DataMonitorEvent;
import org.qortal.event.Event;
import org.qortal.event.EventBus;
import org.qortal.event.Listener;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.utils.Base58;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

@WebSocket
@SuppressWarnings("serial")
public class DataMonitorSocket extends ApiWebSocket implements Listener {

	private static final Logger LOGGER = LogManager.getLogger(DataMonitorSocket.class);

	@Override
	public void configure(WebSocketServletFactory factory) {
		LOGGER.info("configure");

		factory.register(DataMonitorSocket.class);

		EventBus.INSTANCE.addListener(this);
	}

	@Override
	public void listen(Event event) {
		if (!(event instanceof DataMonitorEvent))
			return;

		DataMonitorEvent dataMonitorEvent = (DataMonitorEvent) event;

		for (Session session : getSessions())
			sendDataEventSummary(session, buildInfo(dataMonitorEvent));
	}

	private DataMonitorInfo buildInfo(DataMonitorEvent dataMonitorEvent) {

		return new DataMonitorInfo(
			dataMonitorEvent.getTimestamp(),
			dataMonitorEvent.getIdentifier(),
			dataMonitorEvent.getName(),
			dataMonitorEvent.getService(),
			dataMonitorEvent.getDescription(),
			dataMonitorEvent.getTransactionTimestamp(),
			dataMonitorEvent.getLatestPutTimestamp()
		);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		super.onWebSocketConnect(session);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		super.onWebSocketClose(session, statusCode, reason);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* We ignore errors for now, but method here to silence log spam */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		LOGGER.info("onWebSocketMessage: message = " + message);
	}

	private void sendDataEventSummary(Session session, DataMonitorInfo dataMonitorInfo) {
		StringWriter stringWriter = new StringWriter();

		try {
			marshall(stringWriter, dataMonitorInfo);

			session.getRemote().sendStringByFuture(stringWriter.toString());
		} catch (IOException | WebSocketException e) {
			// No output this time
		}
	}

}
