package io.zbus.transport.http;

import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.ssl.SslHandler;
import io.zbus.kit.logging.Logger;
import io.zbus.kit.logging.LoggerFactory; 


public class MessageCodec extends MessageToMessageCodec<Object, Message> {
	private static final Logger log = LoggerFactory.getLogger(MessageCodec.class);

	private static final String WEBSOCKET_PATH = "/";
	private WebSocketServerHandshaker handshaker;

	@Override
	protected void encode(ChannelHandlerContext ctx, Message msg, List<Object> out) throws Exception {
		//1) WebSocket mode
		if(handshaker != null){//websocket step in, Message To WebSocketFrame
			ByteBuf buf = Unpooled.wrappedBuffer(msg.toBytes());
			WebSocketFrame frame = new TextWebSocketFrame(buf);
			out.add(frame); 
			return;
		}
		
		//2) HTTP mode
		FullHttpMessage httpMsg = null;
		if (msg.getStatus() == null) {// as request
			httpMsg = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(msg.getMethod()),
					msg.getUrl());
		} else {// as response
			httpMsg = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
					HttpResponseStatus.valueOf(Integer.valueOf(msg.getStatus())));
		}
		//content-type and encoding
		String contentType = msg.getHeader(Message.CONTENT_TYPE);
		if(contentType == null) {
			contentType = "text/plain";
		}
		String encoding = msg.getHeader(Message.ENCODING);
		if(encoding == null){
			encoding = "utf-8";
		}
		contentType += "; charset=" + encoding;
		httpMsg.headers().set(Message.CONTENT_TYPE, contentType);
		
		for (Entry<String, String> e : msg.getHeaders().entrySet()) {
			if(e.getKey().equalsIgnoreCase(Message.CONTENT_TYPE)) continue;
			if(e.getKey().equalsIgnoreCase(Message.ENCODING)) continue;
			
			httpMsg.headers().add(e.getKey().toLowerCase(), e.getValue());
		}
		if (msg.getBody() != null) {
			httpMsg.content().writeBytes(msg.getBody());
		}

		out.add(httpMsg);
	}

	@Override
	protected void decode(ChannelHandlerContext ctx, Object obj, List<Object> out) throws Exception {
		//1) WebSocket mode
		if(obj instanceof WebSocketFrame){
			Message msg = decodeWebSocketFrame(ctx, (WebSocketFrame)obj);
			if(msg != null){
				out.add(msg);
			}
			return;
		}
		
		//2) HTTP mode
		if(!(obj instanceof HttpMessage)){
			throw new IllegalArgumentException("HttpMessage object required: " + obj);
		}
		
		HttpMessage httpMsg = (HttpMessage) obj;
		Message msg = new Message();
		Iterator<Entry<String, String>> iter = httpMsg.headers().iterator();
		while (iter.hasNext()) {
			Entry<String, String> e = iter.next();
			if(e.getKey().equalsIgnoreCase(Message.CONTENT_TYPE)){ //encoding and type
				String[] typeInfo = httpContentType(e.getValue());
				msg.setHeader(Message.CONTENT_TYPE, typeInfo[0]);
				if(msg.getHeader(Message.ENCODING) == null) {
					msg.setHeader(Message.ENCODING, typeInfo[1]);
				}
			} else {
				msg.setHeader(e.getKey().toLowerCase(), e.getValue());
			} 
		}

		if (httpMsg instanceof HttpRequest) {
			HttpRequest req = (HttpRequest) httpMsg;
			msg.setMethod(req.getMethod().name());
			msg.setUrl(req.getUri());
		} else if (httpMsg instanceof HttpResponse) {
			HttpResponse resp = (HttpResponse) httpMsg;
			int status = resp.getStatus().code();
			msg.setStatus(status);
		}

		if (httpMsg instanceof FullHttpMessage) {
			FullHttpMessage fullReq = (FullHttpMessage) httpMsg;
			int size = fullReq.content().readableBytes();
			if (size > 0) {
				byte[] data = new byte[size];
				fullReq.content().readBytes(data);
				msg.setBody(data);
			}
		}

		out.add(msg);
	}
	 
	private static String[] httpContentType(String value){
		String type="text/plain", charset="utf-8";
		String[] bb = value.split(";");
		if(bb.length>0){
			type = bb[0].trim();
		}
		if(bb.length>1){
			String[] bb2 = bb[1].trim().split("=");
			if(bb2[0].trim().equalsIgnoreCase("charset")){
				charset = bb2[1].trim();
			}
		}
		return new String[]{type, charset};
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if(msg instanceof FullHttpRequest){
			FullHttpRequest req = (FullHttpRequest) msg; 
			
			//check if websocket upgrade encountered
			if(req.headers().contains("Upgrade") || req.headers().contains("upgrade")) {
				WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
						getWebSocketLocation(req, ctx), null, true, 1024 * 1024 * 1024);
				handshaker = wsFactory.newHandshaker(req);
				if (handshaker == null) {
					WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
				} else {
					handshaker.handshake(ctx.channel(), req);
				}
				return;
			}
		}
		
		super.channelRead(ctx, msg);
	}
 
	private Message decodeWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
		// Check for closing frame
		if (frame instanceof CloseWebSocketFrame) {
			handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
			return null;
		}
		
		if (frame instanceof PingWebSocketFrame) {
			ctx.write(new PongWebSocketFrame(frame.content().retain()));
			return null;
		}
		
		if (frame instanceof TextWebSocketFrame) {
			TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
			return parseMessage(textFrame.content());
		}
		
		if (frame instanceof BinaryWebSocketFrame) {
			BinaryWebSocketFrame binFrame = (BinaryWebSocketFrame) frame;
			return parseMessage(binFrame.content());
		}
		
		log.warn("Message format error: " + frame); 
		return null;
	}
	
	private Message parseMessage(ByteBuf buf){
		int size = buf.readableBytes();
		byte[] data = new byte[size];
		buf.readBytes(data); 
		Message msg = Message.parse(data); 
		if(msg == null){
			log.warn("Message format error: " + new String(data));
		}
		return msg;
	}

	private static String getWebSocketLocation(HttpMessage req, ChannelHandlerContext ctx) {
		String location = req.headers().get(HttpHeaders.Names.HOST) + WEBSOCKET_PATH;
		if (ctx.pipeline().get(SslHandler.class) != null) {
			return "wss://" + location;
		} else {
			return "ws://" + location;
		}
	}
}
