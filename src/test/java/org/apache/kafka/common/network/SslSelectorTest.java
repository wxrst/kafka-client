package org.apache.kafka.common.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.security.ssl.SslFactory;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.test.TestSslUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * SSL选择器测试
 *
 * 其中还运行了一个简单的Socket服务器，该服务器会把请求响应结果输出来。
 *
 * @author wanggang
 *
 */
public class SslSelectorTest extends SelectorTest {

	private Metrics metrics;
	private Map<String, Object> sslClientConfigs;

	@Override
	@Before
	public void setup() throws Exception {
		File trustStoreFile = File.createTempFile("truststore", ".jks");

		Map<String, Object> sslServerConfigs = TestSslUtils.createSslConfig(false, true,
				Mode.SERVER, trustStoreFile, "server");
		sslServerConfigs.put(SslConfigs.PRINCIPAL_BUILDER_CLASS_CONFIG,
				Class.forName(SslConfigs.DEFAULT_PRINCIPAL_BUILDER_CLASS));
		this.server = new EchoServer(sslServerConfigs);
		this.server.start();
		this.time = new MockTime();
		sslClientConfigs = TestSslUtils.createSslConfig(false, false, Mode.SERVER, trustStoreFile,
				"client");

		this.channelBuilder = new SslChannelBuilder(Mode.CLIENT);
		this.channelBuilder.configure(sslClientConfigs);
		this.metrics = new Metrics();
		this.selector = new Selector(5000, metrics, time, "MetricGroup",
				new LinkedHashMap<String, String>(), channelBuilder);
	}

	@Override
	@After
	public void teardown() throws Exception {
		this.selector.close();
		this.server.close();
		this.metrics.close();
	}

	/**
	 * Tests that SSL renegotiation initiated by the server are handled correctly by the client
	 * @throws Exception
	 */
	@Test
	public void testRenegotiation() throws Exception {
		ChannelBuilder channelBuilder = new SslChannelBuilder(Mode.CLIENT) {

			@Override
			protected SslTransportLayer buildTransportLayer(SslFactory sslFactory, String id,
					SelectionKey key) throws IOException {
				SocketChannel socketChannel = (SocketChannel) key.channel();
				SslTransportLayer transportLayer = new SslTransportLayer(id, key,
						sslFactory.createSslEngine(socketChannel.socket().getInetAddress()
								.getHostName(), socketChannel.socket().getPort()), true);
				transportLayer.startHandshake();
				return transportLayer;
			}

		};
		channelBuilder.configure(sslClientConfigs);
		Selector selector = new Selector(5000, metrics, time, "MetricGroup2",
				new LinkedHashMap<String, String>(), channelBuilder);
		try {
			int reqs = 500;
			String node = "0";
			// create connections
			InetSocketAddress addr = new InetSocketAddress("localhost", server.port);
			selector.connect(node, addr, BUFFER_SIZE, BUFFER_SIZE);

			// send echo requests and receive responses
			int requests = 0;
			int responses = 0;
			int renegotiates = 0;
			while (!selector.isChannelReady(node)) {
				selector.poll(1000L);
			}
			selector.send(createSend(node, node + "-" + 0));
			requests++;

			// loop until we complete all requests
			while (responses < reqs) {
				selector.poll(0L);
				if (responses >= 100 && renegotiates == 0) {
					renegotiates++;
					server.renegotiate();
				}
				assertEquals("No disconnects should have occurred.", 0, selector.disconnected()
						.size());

				// handle any responses we may have gotten
				for (NetworkReceive receive : selector.completedReceives()) {
					String[] pieces = asString(receive).split("-");
					assertEquals("Should be in the form 'conn-counter'", 2, pieces.length);
					assertEquals("Check the source", receive.source(), pieces[0]);
					assertEquals("Check that the receive has kindly been rewound", 0, receive
							.payload().position());
					assertEquals("Check the request counter", responses,
							Integer.parseInt(pieces[1]));
					responses++;
				}

				// prepare new sends for the next round
				for (int i = 0; i < selector.completedSends().size() && requests < reqs
						&& selector.isChannelReady(node); i++, requests++) {
					selector.send(createSend(node, node + "-" + requests));
				}
			}
		} finally {
			selector.close();
		}
	}

	@Test
	public void testDisabledRenegotiation() throws Exception {
		String node = "0";
		// create connections
		InetSocketAddress addr = new InetSocketAddress("localhost", server.port);
		selector.connect(node, addr, BUFFER_SIZE, BUFFER_SIZE);

		// send echo requests and receive responses
		while (!selector.isChannelReady(node)) {
			selector.poll(1000L);
		}
		selector.send(createSend(node, node + "-" + 0));
		selector.poll(0L);
		server.renegotiate();
		selector.send(createSend(node, node + "-" + 1));
		long expiryTime = System.currentTimeMillis() + 2000;

		List<String> disconnected = new ArrayList<>();
		while (!disconnected.contains(node) && System.currentTimeMillis() < expiryTime) {
			selector.poll(10);
			disconnected.addAll(selector.disconnected());
		}
		assertTrue("Renegotiation should cause disconnection", disconnected.contains(node));

	}

	/**
	 * Connects and waits for handshake to complete. This is required since SSLTransportLayer
	 * implementation requires the channel to be ready before send is invoked (unlike plaintext
	 * where send can be invoked straight after connect)
	 */
	@Override
	protected void connect(String node, InetSocketAddress serverAddr) throws IOException {
		blockingConnect(node, serverAddr);
	}

}
