object IOvervalueMyPrivacy {
  private[this] var i = 0
  def go = {
    List(1,2,3).foreach(i += _)
    i
  }
}

object Test extends Application {
  assert(IOvervalueMyPrivacy.go == 6)
}
