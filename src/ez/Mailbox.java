package ez;

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
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import ox.Log;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import ez.Email.Address;
import ez.Email.Attachment;
import ez.EmailUtils.EmailData;
import ez.EmailUtils.FetchTextCommand;

public class Mailbox {

  private final String username, password;

  private final Properties props = new Properties();

  private Session session;
  private Store store;
  private IMAPFolder folder;

  public Mailbox(String username, String password) {
    this(username, password, "smtp.gmail.com");
  }

  public Mailbox(String username, String password, String mailServer) {
    this.username = username;
    this.password = password;

    props.put("mail.smtp.host", mailServer);
    props.put("mail.smtp.socketFactory.port", 465);
    props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
    props.put("mail.smtp.auth", true);
    props.put("mail.smtp.port", 465);

    this.session = Session.getInstance(props, new javax.mail.Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(username, password);
      }
    });
  }

  public void sendEmail(String from, String to, String subject, String text) {
    try {
      Message message = new MimeMessage(session);

      message.setFrom(new InternetAddress(from));
      message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
      message.setSubject(subject);
      message.setContent(text, "text/html; charset=utf-8");
      // message.setText(text);

      Transport.send(message);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  @SuppressWarnings("unchecked")
  public Map<Long, String> getContent(List<Long> uids) {
    try {
      Map<Long, EmailData> m = (Map<Long, EmailData>) getFolder().doCommand(new FetchTextCommand(uids));
      return Maps.transformValues(m, (EmailData data) -> {
        return data.bodyText == null ? data.bodyHTML : data.bodyText;
      });
    } catch (Exception e) {
      throw Throwables.propagate(e);
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

  public void get(Query query, Consumer<List<Email>> callback) {
    try {
      final int CHUNK_SIZE = query.includeBody ? 128 : 1024;

      Message[] messages = getFolder().getMessagesByUID(query.fromUID, query.toUID);

      for (int i = 0; i < messages.length; i += CHUNK_SIZE) {
        Message[] buffer = Arrays.copyOfRange(messages, i,
            i + Math.min(CHUNK_SIZE, messages.length - i));
        List<Email> emails = fetch(buffer, query.includeBody);
        callback.accept(emails);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private List<Email> fetch(Message[] buffer, boolean includeBody) throws Exception {
    FetchProfile fp = new FetchProfile();
    fp.add(FetchProfile.Item.ENVELOPE);
    if (includeBody) {
      fp.add(FetchProfile.Item.CONTENT_INFO);
    }

    IMAPFolder folder = getFolder();

    folder.fetch(buffer, fp);

    Map<Long, EmailData> content = Collections.emptyMap();
    if (includeBody) {
      long start = folder.getUID(buffer[0]);
      long end = folder.getUID(buffer[buffer.length - 1]);
      content = (Map<Long, EmailData>) folder.doCommand(new FetchTextCommand(start, end));
    }

    List<Email> emails = new ArrayList<>(buffer.length);
    for (int j = 0; j < buffer.length; j++) {
      Email email = toEmail(folder, (IMAPMessage) buffer[j], content);
      emails.add(email);
    }

    return emails;
  }

  private Email toEmail(IMAPFolder folder, IMAPMessage m, Map<Long, EmailData> content) throws Exception {
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
    String inReplyTo = m.getInReplyTo();
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

    return new Email(uid, messageId, inReplyTo, date, from, to, cc, bcc, replyTo, subject, body, attachments);
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
    if (folder == null) {
      synchronized (this) {
        if (folder == null) {
          try {
            this.store = session.getStore("imaps");

            store.connect("smtp.gmail.com", username, password);

            this.folder = (IMAPFolder) store.getFolder("[Gmail]/All Mail");
            folder.open(Folder.READ_ONLY);
          } catch (Exception e) {
            throw Throwables.propagate(e);
          }
        }
      }
    }

    return folder;
  }

}
