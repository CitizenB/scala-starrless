package scala;

module Predef {

  val True = Boolean.True;
  val False = Boolean.False;

  val List = scala.List;

  def List[a](x: a*): List[a] = x as List[a];

  def error[err](x: String):err = new java.lang.RuntimeException(x).throw;

  def try[a](def block: a): Except[a] =
    new Except(scala.runtime.ResultOrException.tryBlock(block));

  def range(lo: Int, hi: Int): List[Int] = 
    if (lo > hi) List() else lo :: range(lo + 1, hi);

  def while(def condition: Boolean)(def command: Unit): Unit = 
    if (condition) {
      command; while(condition)(command)
    } else {
    }

  trait Until {
    def until(def condition: Boolean): Unit
  }

  def repeat(def command: Unit): Until = 
    new Until {
      def until(def condition: Boolean): Unit = {
	command ; 
	if (condition) {}
	else until(condition)
      }
    }

  type Pair = Tuple2;
  def Pair[a, b](x: a, y: b) = Tuple2(x, y);

  type Triple = Tuple3;
  def Triple[a, b, c](x: a, y: b, z: c) = Tuple3(x, y, z);
}





