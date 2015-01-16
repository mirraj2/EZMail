package ez;

import static com.google.common.collect.Iterables.concat;
import java.time.LocalDateTime;
import java.util.List;

public class Email {

  public static final Address NO_ADDRESS = new Address("UNKNOWN", "Unknown");

  public long id;
  public long uid;
  public String messageId;

  /**
   * The messageID that this email is responding to. This corresponds to the IN-REPLY-TO header.
   */
  public String responseId;

  public LocalDateTime date;
  public Address from;
  public List<Address> to, cc, bcc, replyTo;
  public String subject, body;
  public List<Attachment> attachments;

  public Email(long id, long uid, String messageId, String responseId, LocalDateTime date, String subject) {
    this.id = id;
    this.uid = uid;
    this.messageId = messageId;
    this.responseId = responseId;
    this.date = date;
    this.subject = subject;
  }

  public Email(long uid, String messageId, String inReplyTo, LocalDateTime date, Address from, List<Address> to,
      List<Address> cc, List<Address> bcc, List<Address> replyTo, String subject, String body,
      List<Attachment> attachments) {
    this.uid = uid;
    this.messageId = messageId;
    this.responseId = inReplyTo;
    this.date = date;
    this.from = from == null ? NO_ADDRESS : from;
    this.to = to;
    this.cc = cc;
    this.bcc = bcc;
    this.replyTo = replyTo;
    this.subject = subject;
    this.body = body;
    this.attachments = attachments;
  }

  public boolean isFrom(String address) {
    return address.equals(from.email);
  }

  public Iterable<Address> targetAddresses() {
    return concat(to, cc, bcc);
  }

  @Override
  public String toString() {
    return subject;
  }

  public static class Address {
    public final String email, name;

    public Address(String email, String name) {
      this.email = email;
      this.name = name;
    }

    @Override
    public String toString() {
      return name == null ? email : name;
    }
  }

  public static class Attachment {

    public final String name, type;
    public final long size;

    public Attachment(String name, String type, long size) {
      this.name = name;
      this.type = type;
      this.size = size;
    }
  }

}
