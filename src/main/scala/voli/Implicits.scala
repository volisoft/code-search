package voli

object Implicits {
  def main(args: Array[String]): Unit = {
    val sorted = msort(List(2, 3, 4, 5).reverse)
    println(sorted)
  }

  def merge(xs1: List[Int], xs2: List[Int]): List[Int] = (xs1, xs2) match {
    case (Nil, ys) => ys
    case (xs, Nil) => xs
    case (x :: xs, y :: ys) =>
      if (x < y) x :: merge(xs, y :: ys)
      else y :: merge(x :: xs, ys)
  }

  def msort(xs:List[Int]): List[Int] = {
    if (xs.size < 2) xs
    else {
      val (l, r) = xs.splitAt(xs.length / 2)
      merge(msort(l), msort(r))
    }
  }
}