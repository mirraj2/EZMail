package ez;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.mail.BodyPart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import ox.Log;
import com.google.common.base.Joiner;
import com.sun.mail.iap.Argument;
import com.sun.mail.iap.ByteArray;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.iap.Response;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import com.sun.mail.imap.protocol.BODY;
import com.sun.mail.imap.protocol.FetchResponse;
import com.sun.mail.imap.protocol.IMAPProtocol;
import com.sun.mail.imap.protocol.UID;
import com.sun.mail.util.DecodingException;
import ez.Email.Attachment;

class EmailUtils {

  private static final Session session;

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
    // Log.debug(new String(data.getNewBytes()));

    MimeMessage mm = new MimeMessage(session, data.toByteArrayInputStream());
    EmailData ret = new EmailData();
    recurse(mm, ret, true);
    return ret;
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
        } else if (content instanceof MimeMultipart) {
          recurse((MimeMultipart) content, data, download);
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
          if (part.getContent() instanceof String) {
            data.bodyHTML = (String) part.getContent();
          } else if (part.getContent() instanceof MimeMultipart) {
            recurse((MimeMultipart) part.getContent(), data, download);
          }
        }
      } else if (type.contains("multipart")) {
        recurse((MimeMultipart) part.getContent(), data, download);
      } else if (disposition.equals("attachment") || type.contains("image/") || disposition.contains("inline")
          || type.contains("octet-stream") || type.contains("application/")) {
        data.attachments.add(new Attachment(part.getFileName(), type, part.getSize()));
      } else if (type.contains("rfc822")) {
        recurse((MimeMessage) part.getContent(), data, download);
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

    private long start, end;
    private List<Long> uids;

    public FetchTextCommand(long start, long end) {
      this.start = start;
      this.end = end;
    }

    public FetchTextCommand(List<Long> uids) {
      this.uids = uids;
    }

    @Override
    public Map<Long, EmailData> doCommand(IMAPProtocol protocol) throws ProtocolException {
      Argument args = new Argument();
      if (uids == null) {
        args.writeString(start + ":" + end);
      } else {
        args.writeString(Joiner.on(',').join(uids));
      }
      args.writeString("BODY.PEEK[1]");
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

  // public static void main(String[] args) throws Exception {
  // Config config = Config.load("babel");
  // Mailbox box = new Mailbox(config.get("username"), config.get("password"));
  // IMAPFolder folder = box.getFolder();
  // Log.debug("Connected!");
  // folder.doCommand(new ProtocolCommand() {
  // @Override
  // public Object doCommand(IMAPProtocol protocol) throws ProtocolException {
  // // protocol.capability();
  // // Log.debug(protocol.getCapabilities());
  // Argument args = new Argument();
  // args.writeString("DEFLATE");
  // Response[] resp = protocol.command("COMPRESS", args);
  // Log.debug(resp);
  //
  // Argument args2 = new Argument();
  // args2.writeString("1:10");
  // args.writeString("BODY.PEEK[1]");
  // // args.writeString("BODY[TEXT]");
  // Response[] r = protocol.command("UID FETCH", args);
  // Response response = r[r.length - 1];
  // Log.debug(response);
  //
  // return null;
  // }
  // });
  // }

}
