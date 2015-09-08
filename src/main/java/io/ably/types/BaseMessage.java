package io.ably.types;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator;

import io.ably.util.Base64Coder;
import io.ably.util.Crypto.ChannelCipher;
import io.ably.util.Serialisation;

@JsonInclude(Include.NON_DEFAULT)
public class BaseMessage implements Cloneable {
	/**
	 * A unique id for this message
	 */
	public String id;

	/**
	 * The timestamp for this message
	 */
	public long timestamp;

	/**
	 * The id of the publisher of this message
	 */
	public String clientId;

	/**
	 * The connection id of the publisher of this message
	 */
	public String connectionId;

	/**
	 * Any transformation applied to the data for this message
	 */
	public String encoding;

	/**
	 * The message payload.
	 */
	public Object data;

	/**
	 * Generate a String summary of this BaseMessage
	 * @return string
	 */
	public void getDetails(StringBuilder builder) {
		if(clientId != null)
			builder.append(" clientId=").append(clientId);
		if(connectionId != null)
			builder.append(" connectionId=").append(connectionId);
		if(data != null)
			builder.append(" data=").append(data);
		if(encoding != null)
			builder.append(" encoding=").append(encoding);
		if(id != null)
			builder.append(" id=").append(id);
	}

	public void decode(ChannelOptions opts) throws AblyException {
		if(encoding != null) {
			String[] xforms = encoding.split("\\/");
			int i = 0, j = xforms.length;
			try {
				while((i = j) > 0) {
					Matcher match = xformPattern.matcher(xforms[--j]);
					if(!match.matches()) break;
					String xform = match.group(1).intern();
					if(xform == "base64") {
						data = Base64Coder.decode((String)data);
						continue;
					}
					if(xform == "utf-8") {
						try { data = new String((byte[])data, "UTF-8"); } catch(UnsupportedEncodingException e) {}
						continue;
					}
					if(xform == "json") {
						try {
							String jsonText = ((String)data).trim();
							if(jsonText.charAt(0) == '[')
								data = new JSONArray(jsonText);
							else
								data = new JSONObject(jsonText);
						}
						catch(JSONException e) { throw AblyException.fromThrowable(e); }
						continue;
					}
					if(xform == "cipher" && opts != null && opts.encrypted) {
						data = opts.getCipher().decrypt((byte[])data);
						continue;
					}
					break;
				}
			} finally {
				encoding = (i <= 0) ? null : join(xforms, '/', 0, i);
			}
		}
	}

	public void encode(ChannelOptions opts) throws AblyException {
		if(data instanceof JSONObject || data instanceof JSONArray) {
			data = data.toString();
			encoding = ((encoding == null) ? "" : encoding + "/") + "json";
		}
		if(opts != null && opts.encrypted) {
			if(data instanceof String) {
				try { data = ((String)data).getBytes("UTF-8"); } catch(UnsupportedEncodingException e) {}
				encoding = ((encoding == null) ? "" : encoding + "/") + "utf-8";
			}
			if(!(data instanceof byte[])) {
				throw new AblyException("Unable to encode message data (incompatible type)", 400, 40000);
			}
			ChannelCipher cipher = opts.getCipher();
			data = cipher.encrypt((byte[])data);
			encoding = ((encoding == null) ? "" : encoding + "/") + "cipher+" + cipher.getAlgorithm();
		}
	}

	protected void serializeFields(JsonGenerator generator) throws IOException {
		if(data != null) {
			if(data instanceof byte[]) {
				byte[] dataBytes = (byte[])data;
				if(generator.getCodec() == Serialisation.jsonObjectMapper) {
					generator.writeStringField("data", new String(Base64Coder.encode(dataBytes)));
					encoding = (encoding == null) ? "base64" : encoding + "/base64";
				} else {
					generator.writeBinaryField("data", dataBytes);
				}
			} else {
				generator.writeStringField("data", data.toString());
			}
			if(encoding != null) generator.writeStringField("encoding", encoding);
		}
		if(clientId != null) generator.writeStringField("clientId", clientId);
		if(connectionId != null) generator.writeStringField("connectionId", connectionId);
	}

	/* trivial utilities for processing encoding string */
	private static Pattern xformPattern = Pattern.compile("([\\-\\w]+)(\\+([\\-\\w]+))?");
	private String join(String[] elements, char separator, int start, int end) {
		StringBuilder result = new StringBuilder(elements[start++]);
		for(int i = start; i < end; i++)
			result.append(separator).append(elements[i]);
		return result.toString();
	}
}