package clashcode.wordguess

import akka.actor._
import scala.collection.mutable

import messages._
import scala.io.Source
import java.io.{FileNotFoundException, FileWriter}

class WordGuesserClient(playerName: String, gameServer: ActorRef) extends Actor {
      
  // IMPORTANT: 
  // 1) start by requesting a game to the server
  // 2) the workflow is ASYNCHRONOUS; so don't participate in a game
  // until you know you are in one.

  // Main methods at your disposal:
  // requestGame()
  // makeGuess('a')
  val alphabet = ('a' to 'z').toSet

  // char probability map
  val charProbabilityMap: mutable.HashMap[Char, Int] = mutable.HashMap.empty

  case class Attempt(status: GameStatus, char: Char)
  var maybePreviousAttempt: Option[Attempt] = None

  // game history map
  case class Game(gameId: Int, knownLetters: Set[Char] = Set.empty, deadLetters: Set[Char] = Set.empty) {
    override def toString = s"$gameId: known: ${knownLetters.mkString.toUpperCase} , dead: ${deadLetters.mkString.toUpperCase}"
  }
  val gamesHistory = mutable.HashMap[Int, Game]()

  object Persistence {
    def persistProbabilityMap() {
      val writer = new FileWriter("./char-map.txt")
      writer.write(charProbabilityMap.toList.sortBy(-_._2).map(v => s"${v._1}:${v._2}").mkString("\n"))
      writer.close()
    }

    def restoreProbabilityMap() {
      try {
        val lines = Source.fromFile("./char-map.txt").getLines()
        lines.foreach(line => {
          charProbabilityMap.put(line.split(":")(0).charAt(0), line.split(":")(1).toInt)
        })
      } catch {
        case e: FileNotFoundException =>
      }
    }

    def persistGameHistory() {
      val writer = new FileWriter("./game-history.txt")
      gamesHistory.values.toList.sortBy(_.gameId).foreach(game =>
        writer.write(s"${game.gameId.toString} ${game.knownLetters.mkString} ${game.deadLetters.mkString}\n")
      )
      writer.close()
    }

    def restoreGameHistory() {
      try {
        val lines = Source.fromFile("./game-history.txt").getLines()
        lines.filter(_.length > 0).foreach(line => {
          line.split(" ") match {
            case Array(gid, known, dead) => gamesHistory.put(gid.toInt, Game(gid.toInt, known.toCharArray.toSet, dead.toCharArray.toSet))
            case Array(gid, known) => gamesHistory.put(gid.toInt, Game(gid.toInt, known.toCharArray.toSet, Set.empty[Char]))
            case _ =>
          }
          //gamesHistory.put(ps(0).toInt, Game(ps(0).toInt, ps(1).toCharArray.toSet, if (ps.length > 2) ps(2).toCharArray.toSet else Set.empty[Char]))
        })
      } catch {
        case e: FileNotFoundException =>
      }
    }
  }

    // Incoming messages from the server are handled here
  override def receive = {
    // When a game was accepted or after a guess was made
    case status: GameStatus => {
      // show current status
      println(status)

      rethinkResult(status)

      val game = gamesHistory.getOrElseUpdate(status.gameId, Game(status.gameId))
      // show char probability list
      println("Char map: " + charProbabilityMap.toSeq.sortBy(-_._2).map(_._1).mkString)
      // show game from history
      println(game)

      // calculate new guess
      val openedLetters = status.letters.flatMap(_.map(c => c.toLower)).toSet
      val possibleChars = alphabet -- (openedLetters ++ game.deadLetters)
      val possibleKnownChars = game.knownLetters -- openedLetters
      val possibleCharsByProbability = charProbabilityMap.filter(p => possibleChars.contains(p._1)).toList.sortBy(-_._2).map(_._1)

      val nextGuess =
        if (!possibleKnownChars.isEmpty) possibleKnownChars.head
        else if (!possibleCharsByProbability.isEmpty) possibleCharsByProbability.head
        else if (!possibleChars.isEmpty) possibleChars.head
        else readChar()

      println(s"Next guess: $nextGuess")

      makeGuess(nextGuess)

      maybePreviousAttempt = Some(Attempt(status, nextGuess))

      println()
      Persistence.persistGameHistory()
    }
    // When the game was won
    case GameWon(status) => {

      rethinkResult(status)
      maybePreviousAttempt = None
      println(status)
      requestGame()

    }
    // When the game was lost
    case GameLost(status) => {

      rethinkResult(status)
      maybePreviousAttempt = None

      println(status)

      gamesHistory.get(status.gameId) match {
        case Some(game) => broadCastMsg(s"${status.gameId.toString} ${game.knownLetters.mkString} ${game.deadLetters.mkString}")
        case None => // do nothing
      }

      requestGame()
    }
    // If there are no more available games (rare, but could happen)
    case NoAvailableGames() => {
      println("No more games")
      stopApplication()
    }
    // If the client (you) made a guess although no game was requested (or is over)
    case NotPlayingError() => {
      println("No game was requested (or is over)")
    }
    // When an chat message arrives 
    case MsgToAll(msg) => {
      (msg.split("\\s+") match {
        case Array(gid, known, dead) => Right((gid.toInt, known.toSet, dead.toSet))
        case Array(gid, known) => Right((gid.toInt, known.toSet, Set.empty[Char]))
        case _ => Left(s"Couldn't parse message: $msg")
      }) match {
        case Right(t) => {
          val game = gamesHistory.get(t._1).getOrElse(Game(t._1, Set.empty, Set.empty))
          gamesHistory.put(game.gameId, game.copy(knownLetters = game.knownLetters ++ t._2, deadLetters = game.deadLetters ++ t._3))
          println("Game updated by fellow advice!")
        }
        case Left(e) => println(e)
      }
    }
  }


  def rethinkResult(status: GameStatus) {
    def updateProbabilityMap(char: Char, success: Boolean) {
      val weight = charProbabilityMap.getOrElse(char, 0)
      charProbabilityMap.update(char, weight + (if (success) 1 else -1))
      Persistence.persistProbabilityMap()
    }

    def updateGameHistory(game: Game, char: Char, success: Boolean) {
      gamesHistory.put(game.gameId,
        if (success) game.copy(knownLetters = game.knownLetters + char)
        else game.copy(deadLetters = game.deadLetters + char))
    }

    maybePreviousAttempt match {
      case Some(previousAttempt) => {
        val previousGame = gamesHistory.getOrElseUpdate(previousAttempt.status.gameId, Game(previousAttempt.status.gameId))
        val wasSuccess: Boolean = previousAttempt.status.gameId == status.gameId && previousAttempt.status.remainingTries == status.remainingTries
        val wasKnownChar: Boolean = previousGame.knownLetters.contains(previousAttempt.char)

        if (!wasKnownChar) {
          updateProbabilityMap(previousAttempt.char, wasSuccess)
          updateGameHistory(previousGame, previousAttempt.char, wasSuccess)
        }

      }
      case None => // do nothing
    }

  }

  // Request a game from the server; start by doing this
  def requestGame() {
    gameServer ! RequestGame(playerName)
  }
  // You try to guess the word by making guesses
  def makeGuess(letter: Char) {
    gameServer ! MakeGuess(letter)
  }
  // You can stop your local app with this (shutdown the actor-system)
  def stopApplication() {
    context.system.shutdown()
  }
  // You can send a message to all other players (to chat?)
  def broadCastMsg(msg: String) {
    gameServer ! SendToAll(msg)
  }


  println("Actor starting...")
  Persistence.restoreProbabilityMap()
  Persistence.restoreGameHistory()
  requestGame()

}