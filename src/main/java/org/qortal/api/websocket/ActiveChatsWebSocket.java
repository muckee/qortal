package org.qortal.api.websocket;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.qortal.controller.ChatNotifier;
import org.qortal.crypto.Crypto;
import org.qortal.data.chat.ActiveChats;
import org.qortal.data.transaction.ChatTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.qortal.data.chat.ChatMessage.Encoding;

@WebSocket
@SuppressWarnings("serial")
public class ActiveChatsWebSocket extends ApiWebSocket {

	@Override
	public void configure(WebSocketServletFactory factory) {
		factory.register(ActiveChatsWebSocket.class);
	}

	@OnWebSocketConnect
	@Override
	public void onWebSocketConnect(Session session) {
		Map<String, String> pathParams = getPathParams(session, "/{address}");

		String address = pathParams.get("address");
		if (address == null || !Crypto.isValidAddress(address)) {
			session.close(4001, "invalid address");
			return;
		}

		AtomicReference<String> previousOutput = new AtomicReference<>(null);

		ChatNotifier.Listener listener = chatTransactionData -> onNotify(session, chatTransactionData, address, previousOutput);
		ChatNotifier.getInstance().register(session, listener);

		this.onNotify(session, null, address, previousOutput);
	}

	@OnWebSocketClose
	@Override
	public void onWebSocketClose(Session session, int statusCode, String reason) {
		ChatNotifier.getInstance().deregister(session);
	}

	@OnWebSocketError
	public void onWebSocketError(Session session, Throwable throwable) {
		/* ignored */
	}

	@OnWebSocketMessage
	public void onWebSocketMessage(Session session, String message) {
		if (Objects.equals(message, "ping")) {
			session.getRemote().sendStringByFuture("pong");
		}
	}

	private void onNotify(Session session, ChatTransactionData chatTransactionData, String ourAddress, AtomicReference<String> previousOutput) {
		// If CHAT has a recipient (i.e. direct message, not group-based) and we're neither sender nor recipient, then it's of no interest
		if (chatTransactionData != null) {
			String recipient = chatTransactionData.getRecipient();

			if (recipient != null && (!recipient.equals(ourAddress) && !chatTransactionData.getSender().equals(ourAddress)))
				return;
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			ActiveChats activeChats = repository.getChatRepository().getActiveChats(ourAddress, getTargetEncoding(session));

			StringWriter stringWriter = new StringWriter();

			marshall(stringWriter, activeChats);

			// Only output if something has changed
			String output = stringWriter.toString();
			if (output.equals(previousOutput.get()))
				return;

			previousOutput.set(output);
			session.getRemote().sendStringByFuture(output);
		} catch (DataException | IOException | WebSocketException e) {
			// No output this time?
		}
	}

	private Encoding getTargetEncoding(Session session) {
		// Default to Base58 if not specified, for backwards support
		Map<String, List<String>> queryParams = session.getUpgradeRequest().getParameterMap();
		List<String> encodingList = queryParams.get("encoding");
		String encoding = (encodingList != null && encodingList.size() == 1) ? encodingList.get(0) : "BASE58";
		return Encoding.valueOf(encoding);
	}

}
