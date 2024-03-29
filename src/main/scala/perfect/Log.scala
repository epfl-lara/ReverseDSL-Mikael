package perfect
/**
  * Created by Mikael on 15/03/2017.
  */

import scala.language.dynamics
object Log extends Log with Dynamic {
  var activate = false
  def activated[A](execution: =>A): A = synchronized {
    val prevActivate = Log.activate
    Log.activate = true
    val res = execution
    Log.activate = prevActivate
    res
  }
  def prefix_activated(p: String): Log = new Log {
    override def apply(s: Any) = Log.activated {
      super.apply(p + s)
    }
  }
  def prefix(p: String): Log = new Log {
    override def apply(s: Any) = super.apply(p+s)
  }
  def time(p: String): Log = new Log {
    val startTime = System.currentTimeMillis()
    override def apply(s: Any) = if(activate) {
      val elapsedTime = System.currentTimeMillis() - startTime
      super.apply(s"Time: $p in ${elapsedTime}ms")
    }
  }
  def selectDynamic(name: String) = prefix(name.replaceAll("_", " ") + ": ")
}

trait Log {
  import Log._
  def apply(s: Any) = if(activate) println(s.toString.replaceAll("""\$""", "").replaceAll("""ValDef\((\w+), ?\S+, ?Set\(\)\)""", "$1"))
  def =:[A](s: A): A = {
    apply(s)
    s
  }
  def /:[A](s: A): A = {
    this.=:(s)
  }
  def /::[A](s: Stream[A]): Stream[A] = {
    s.zipWithIndex.map{ case(x, i) =>
      this.apply(s"Partial solution #${i+1}\n"+x)
      x
    }
  }
  def :=[A](s: A): A = {
    apply(s)
    s
  }
}
