package org.ieee_passau.models

import java.util.Date

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{ForeignKeyQuery, ProvenShape}

case class Feedback(id: Option[Int], userId: Int, rating: Int, pro: Option[String], con: Option[String],
                    freetext: Option[String], created: Date)  extends Entity[Feedback] {
  override def withId(id: Int): Feedback = this.copy(id = Some(id))
}

class Feedbacks(tag: Tag) extends TableWithId[Feedback](tag, "feedback") {
  def userId: Rep[Int] = column[Int]("user_id")
  def rating: Rep[Int] = column[Int]("rating")
  def pro: Rep[String] = column[String]("pro")
  def con: Rep[String] = column[String]("con")
  def freetext: Rep[String] = column[String]("freetext")
  def created: Rep[Date] = column[Date]("created")(DateSupport.dateMapper)

  def user: ForeignKeyQuery[Users, User] = foreignKey("user_fk", userId, Users)(_.id)

  override def * : ProvenShape[Feedback] = (id.?, userId, rating, pro.?, con.?, freetext.?, created) <> (Feedback.tupled, Feedback.unapply)
}
object Feedbacks extends TableQuery(new Feedbacks(_))
