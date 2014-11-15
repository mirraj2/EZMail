package ez;

import jasonlib.Log;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.mail.FetchProfile;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Message.RecipientType;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import ez.Email.Address;
import ez.Email.Attachment;
import ez.EmailUtils.EmailData;
import ez.EmailUtils.FetchTextCommand;

public class Mailbox {

  private final IMAPFolder folder;

  public Mailbox(String username, String password) {
    try {
      Properties props = new Properties();
      props.put("mail.smtp.host", "smtp.gmail.com");
      props.put("mail.smtp.socketFactory.port", 465);
      props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
      props.put("mail.smtp.auth", true);
      props.put("mail.smtp.port", 465);

      Session session = Session.getDefaultInstance(props);
      Store store = session.getStore("imaps");

      store.connect("smtp.gmail.com", username, password);

      folder = (IMAPFolder) store.getFolder("[Gmail]/All Mail");
      folder.open(Folder.READ_ONLY);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public Email get(long uid) {
    AtomicReference<Email> ref = new AtomicReference<>();
    get(Query.from(uid).to(uid).body(), (List<Email> emails) -> {
      if (!emails.isEmpty()) {
        ref.set(emails.get(0));
      }
    });
    return ref.get();
  }

  @SuppressWarnings("unchecked")
  public void get(Query query, Consumer<List<Email>> callback) {
    try {
      final int CHUNK_SIZE = query.includeBody ? 64 : 1024;

      long fromId = query.fromUID;
      long toId = query.toUID;

      Message[] messages = folder.getMessagesByUID(fromId, toId);

      FetchProfile fp = new FetchProfile();
      fp.add(FetchProfile.Item.ENVELOPE);
      if (query.includeBody) {
        fp.add(FetchProfile.Item.CONTENT_INFO);
      }

      Message[] buffer = new Message[Math.min(CHUNK_SIZE, messages.length)];
      for (int i = 0; i < messages.length; i += CHUNK_SIZE) {
        int bufferSize = Math.min(CHUNK_SIZE, messages.length - i);
        System.arraycopy(messages, i, buffer, 0, bufferSize);

        folder.fetch(buffer, fp);

        Map<Long, EmailData> content = Collections.emptyMap();
        if (query.includeBody) {
          long start = folder.getUID(buffer[0]);
          long end = folder.getUID(buffer[buffer.length - 1]);
          content = (Map<Long, EmailData>) folder.doCommand(new FetchTextCommand(start, end));
        }

        List<Email> emails = new ArrayList<>(bufferSize);

        for (int j = 0; j < bufferSize; j++) {
          Email email = toEmail((IMAPMessage) buffer[j], content);
          emails.add(email);
        }

        callback.accept(emails);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Email toEmail(IMAPMessage m, Map<Long, EmailData> content) throws Exception {
    long uid = folder.getUID(m);
    String messageId = m.getMessageID();

    if (m.getFrom().length > 1) {
      throw new IllegalStateException("Expected one FROM address, but had " + m.getFrom().length);
    }

    Address from = m.getFrom().length == 0 ? null : converter.apply(m.getFrom()[0]);
    List<Address> to = convert(m.getRecipients(RecipientType.TO));
    List<Address> cc = convert(m.getRecipients(RecipientType.CC));
    List<Address> bcc = convert(m.getRecipients(RecipientType.BCC));
    List<Address> replyTo = convert(m.getReplyTo());
    LocalDateTime date = LocalDateTime.ofInstant(m.getSentDate().toInstant(), ZoneId.systemDefault());
    String subject = m.getSubject();
    String body;
    List<Attachment> attachments;

    EmailData data = content.get(uid);
    if (data == null) {
      body = null;
      attachments = null;
    } else {
      EmailUtils.parse(m, data);
      attachments = data.attachments;

      body = data.bodyText;
      if (body == null) {
        if (data.bodyHTML == null) {
          Log.warn("No body for email with uid: " + uid);
        } else {
          // Log.info("Using html body because no plaintext available.");
          body = data.bodyHTML;
        }
      } else if (body.length() == 0) {
        Log.warn("Email has empty text: " + uid);
      }

    }

    return new Email(uid, messageId, date, from, to, cc, bcc, replyTo, subject, body, attachments);
  }

  private final Function<javax.mail.Address, Address> converter = (javax.mail.Address a) -> {
    InternetAddress b = (InternetAddress) a;
    return new Address(b.getAddress(), b.getPersonal());
  };

  private List<Address> convert(javax.mail.Address[] addresses) {
    if (addresses == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(addresses).map(converter).collect(Collectors.toList());
  }

  public IMAPFolder getFolder() {
    return folder;
  }

}
