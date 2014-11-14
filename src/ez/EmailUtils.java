package ez;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.mail.BodyPart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
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
import ez.Email.Attachment;

class EmailUtils {

  private static final Session session;

  static {
    Properties props = new Properties();
    props.setProperty("mail.store.protocol", "imap");
    props.setProperty("mail.mime.base64.ignoreerrors", "true");
    props.setProperty("mail.imap.partialfetch", "false");
    props.setProperty("mail.imaps.partialfetch", "false");
    session = Session.getInstance(props, null);
  }

  static EmailData parse(ByteArray data) throws Exception {
    MimeMessage mm = new MimeMessage(session, data.toByteArrayInputStream());
    EmailData ret = new EmailData();
    recurse(mm, ret, true);
    return ret;
  }

  static void parse(IMAPMessage message, EmailData data) {
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
        data.bodyText = (String) message.getContent();
      }
    } else if (type.contains("text/html")) {
      if (download) {
        data.bodyHTML = (String) message.getContent();
      }
    } else if (type.contains("multipart")) {
      recurse((MimeMultipart) message.getContent(), data, download);
    } else {
      log("Unhandled mimemessage: " + type);
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
      } else if (disposition.equals("attachment") || type.contains("image/") || disposition.contains("inline")) {
        data.attachments.add(new Attachment(part.getFileName(), type, part.getSize()));
      } else if (type.contains("rfc822")) {
        recurse((IMAPNestedMessage) part.getContent(), data, download);
      } else {
        log("Unhandled multipart: " + type);
        log(disposition);
      }
    }
  }

  private static void log(Object o) {
    System.out.println(o);
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
      args.writeString("BODY[TEXT]");
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
          e.printStackTrace();
          ret.put(uid.uid, EmailData.none());
        }
      }

      protocol.notifyResponseHandlers(r);

      return ret;
    }

  }

}
