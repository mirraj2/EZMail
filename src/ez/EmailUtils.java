package ez;

import jasonlib.Log;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.mail.BodyPart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.sun.mail.iap.Argument;
import com.sun.mail.iap.ByteArray;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.iap.Response;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.IMAPNestedMessage;
import com.sun.mail.imap.protocol.BODY;
import com.sun.mail.imap.protocol.FetchResponse;
import com.sun.mail.imap.protocol.IMAPProtocol;
import com.sun.mail.imap.protocol.UID;
import com.sun.mail.util.DecodingException;
import ez.Email.Attachment;

class EmailUtils {

  private static final Session session;
  private static final String NEXT_PART = "------=_NextPart";

  static {
    System.setProperty("mail.mime.base64.ignoreerrors", "true");

    Properties props = new Properties();
    props.setProperty("mail.store.protocol", "imap");
    props.setProperty("mail.mime.base64.ignoreerrors", "true");
    props.setProperty("mail.imap.partialfetch", "false");
    props.setProperty("mail.imaps.partialfetch", "false");
    session = Session.getInstance(props, null);
  }

  static EmailData parse(ByteArray data) throws Exception {
    String s = new String(data.getNewBytes());
    if (s.contains(NEXT_PART)) {
      try {
        return parseMultipart(s);
      } catch (Exception e) {
        Log.error(s);
        throw Throwables.propagate(e);
      }
    }

    MimeMessage mm = new MimeMessage(session, data.toByteArrayInputStream());
    EmailData ret = new EmailData();
    recurse(mm, ret, true);
    return ret;
  }

  private static EmailData parseMultipart(String s) {
    EmailData ret = new EmailData();
    int i = s.indexOf(NEXT_PART);
    while (i != -1) {
      int i2 = s.indexOf('\n', i);
      int j = s.indexOf(NEXT_PART, i + 1);
      if (j == -1) {
        String leftover = s.substring(i2 + 1).trim();
        if (!leftover.isEmpty()) {
          Log.warn("Threw out: " + leftover.length() + " bytes at the end.");
        }
        break;
      }
      String body = s.substring(i2 + 1, j);
      parseMultipart(body, ret);
      i = j;
    }
    return ret;
  }

  private static void parseMultipart(String body, EmailData data) {
    Headers headers = parseHeaders(body);

    String t = headers.contentType;

    if (t == null) {
      return;
    }

    if (headers.disposition != null && headers.disposition.equalsIgnoreCase("attachment")) {
      Log.warn("Throwing out attachment of size: " + headers.body.length());
      return;
    }

    if (t.contains("text/plain")) {
      data.bodyText = headers.body;
    } else if (t.contains("text/html")) {
      data.bodyHTML = headers.body;
    } else if (t.contains("multipart") || t.contains("image")) {
      Log.warn("Throwing out " + headers.body.length() + " bytes of data.");
    } else {
      throw new RuntimeException("Unhandled type: " + t);
    }
  }

  private static Headers parseHeaders(String body) {
    Iterator<String> iter = Splitter.on('\n').trimResults().split(body).iterator();

    Headers ret = new Headers();

    while (true) {
      String line = iter.next();
      if (line.isEmpty()) {
        break;
      }
      int i = line.indexOf(": ");
      if (i != -1) {
        String key = line.substring(0, i);
        String value = line.substring(i + 2);
        if (value.endsWith(";")) {
          value = value.substring(0, value.length() - 1);
        }

        if (key.equalsIgnoreCase("content-type")) {
          ret.contentType = value;
        } else if (key.equalsIgnoreCase("content-transfer-encoding")) {
          // do nothing
        } else if (key.equalsIgnoreCase("content-disposition")) {
          ret.disposition = value;
        } else {
          Log.warn("Unhandled: " + line);
        }
      } else {
        int j = line.indexOf("=");
        if (j != -1) {
          String key = line.substring(0, j);
          String value = line.substring(j + 1);
          if (key.equalsIgnoreCase("charset")) {
            ret.charset = value;
          } else if (key.equalsIgnoreCase("boundary") || key.equalsIgnoreCase("name")) {
            // skip
          } else {
            Log.warn("Unhandled: " + line);
          }
        } else {
          Log.warn("Unhandled line: " + line);
        }
      }
    }

    ret.body = Joiner.on('\n').join(iter);

    return ret;
  }

  static class Headers {
    String contentType, charset, disposition, body;
  }

  static void parse(IMAPMessage message, EmailData data) {
    if (data.attachments == null) {
      data.attachments = new ArrayList<>(0);
    }
    try {
      recurse(message, data, false);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static void recurse(MimeMessage message, EmailData data, boolean download) throws Exception {
    String type = message.getContentType().toLowerCase();
    if (type.contains("text/plain")) {
      if (download) {
        try {
          data.bodyText = (String) message.getContent();
        } catch (DecodingException e) {
          Log.error("Error decoding.");
          e.printStackTrace();
        }
      }
    } else if (type.contains("text/html")) {
      if (download) {
        Object content = message.getContent();
        if (content instanceof ByteArrayInputStream) {
          ByteArrayInputStream bais = (ByteArrayInputStream) content;
          byte[] bytes = new byte[bais.available()];
          bais.read(bytes);
          data.bodyHTML = new String(bytes, Charset.forName("UTF-8"));
          bais.close();
        } else {
          data.bodyHTML = (String) content;
        }
      }
    } else if (type.contains("multipart")) {
      recurse((MimeMultipart) message.getContent(), data, download);
    } else {
      Log.warn("Unhandled mimemessage: " + type);
    }
  }

  private static void recurse(MimeMultipart multi, EmailData data, boolean download) throws Exception {
    for (int i = 0; i < multi.getCount(); i++) {
      BodyPart part = multi.getBodyPart(i);
      String type = part.getContentType().toLowerCase();
      String disposition = part.getDisposition();
      if (disposition == null) {
        disposition = "";
      } else {
        disposition = disposition.toLowerCase();
      }
      if (type.contains("text/plain")) {
        if (download) {
          data.bodyText = (String) part.getContent();
        }
      } else if (type.contains("text/html")) {
        if (download) {
          data.bodyHTML = (String) part.getContent();
        }
      } else if (type.contains("multipart")) {
        recurse((MimeMultipart) part.getContent(), data, download);
      } else if (disposition.equals("attachment") || type.contains("image/") || disposition.contains("inline")
          || type.contains("octet-stream") || type.contains("application/")) {
        data.attachments.add(new Attachment(part.getFileName(), type, part.getSize()));
      } else if (type.contains("rfc822")) {
        recurse((IMAPNestedMessage) part.getContent(), data, download);
      } else {
        Log.warn("Unhandled multipart: " + type);
        Log.warn(disposition);
        data.attachments.add(new Attachment(part.getFileName(), type, part.getSize()));
      }
    }
  }

  static class EmailData {
    List<Attachment> attachments = new ArrayList<>(0);
    String bodyText, bodyHTML;

    public static EmailData none() {
      EmailData ret = new EmailData();
      ret.attachments = null;
      return ret;
    }
  }

  static class FetchTextCommand implements IMAPFolder.ProtocolCommand {

    final long start, end;

    public FetchTextCommand(long start, long end) {
      this.start = start;
      this.end = end;
    }

    @Override
    public Map<Long, EmailData> doCommand(IMAPProtocol protocol) throws ProtocolException {
      Argument args = new Argument();
      args.writeString(start + ":" + end);
      args.writeString("BODY[]");
      // args.writeString("BODY[TEXT]");
      Response[] r = protocol.command("UID FETCH", args);
      Response response = r[r.length - 1];
      protocol.handleResult(response);

      Map<Long, EmailData> ret = new HashMap<>();

      for (int i = 0; i < r.length - 1; i++) {
        FetchResponse fetch = (FetchResponse) r[i];
        UID uid = (UID) fetch.getItem(UID.class);
        BODY body = (BODY) fetch.getItem(BODY.class);
        try {
          ret.put(uid.uid, parse(body.getByteArray()));
        } catch (Exception e) {
          Log.error("Problem with UID=" + uid.uid);
          e.printStackTrace();
          ret.put(uid.uid, EmailData.none());
        }
      }

      protocol.notifyResponseHandlers(r);

      return ret;
    }

  }

}
