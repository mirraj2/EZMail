package ez;

public class Query {

  private static final long BIGGEST_UID = 10_000_000;

  long fromUID, toUID;
  boolean includeBody = false;

  private Query(long fromUID, long toUID) {
    this.fromUID = fromUID;
    this.toUID = toUID;
  }

  public Query body() {
    includeBody = true;
    return this;
  }

  public Query to(long uid) {
    toUID = uid;
    return this;
  }

  public static Query all() {
    return new Query(1, BIGGEST_UID);
  }

  public static Query from(long uid) {
    return new Query(uid, BIGGEST_UID);
  }

}
