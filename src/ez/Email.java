package ez;

import java.time.LocalDateTime;
import java.util.List;

public class Email {

  public final long uid;
  public final String messageId;
  public final LocalDateTime date;
  public final Address from;
  public final List<Address> to, cc, bcc, replyTo;
  public final String subject, body;
  public final List<Attachment> attachments;

  public Email(long uid, String messageId, LocalDateTime date, Address from, List<Address> to, List<Address> cc,
      List<Address> bcc, List<Address> replyTo, String subject, String body, List<Attachment> attachments) {
    this.uid = uid;
    this.messageId = messageId;
    this.date = date;
    this.from = from;
    this.to = to;
    this.cc = cc;
    this.bcc = bcc;
    this.replyTo = replyTo;
    this.subject = subject;
    this.body = body;
    this.attachments = attachments;
  }

  public static class Address {
    public final String email, name;

    public Address(String email, String name) {
      this.email = email;
      this.name = name;
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
