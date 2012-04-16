package com.ergodicity.engine.core.model

sealed trait Security {
  def isin: String
}

sealed trait Derivative extends Security

case class Future(isin: String, shortIsin: String, isinId: Long, name: String) extends Derivative