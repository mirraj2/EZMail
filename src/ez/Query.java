package ez;

public class Query {

  final long fromUID;
  boolean includeBody = false;

  private Query(long fromUID) {
    this.fromUID = fromUID;
  }

  public Query body() {
    includeBody = true;
    return this;
  }

  public static Query all() {
    return new Query(1);
  }

  public static Query from(long uid) {
    return new Query(uid);
  }

}
