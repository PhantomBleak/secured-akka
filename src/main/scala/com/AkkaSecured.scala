package com

import akka.actor.{Actor, ActorRef, ActorSystem, Props}

import scala.concurrent.Await
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

//we can only transmit vector clock on Normal Message to reduce the cost

object SecureActor {
  def props(): Props = Props(new SecureActor())
  val OverallTimeOut = 10 seconds
  val MaxActorNumber: Int = 100
  //add error type
  //Ask if we should send by value: I guessed that actors wont change it so its okay
  case class NormalMessageWithVectorClock(message: Any, vc: Array[Int])
  case class NormalMessage(message: Any)
  case class ErrorMessage(vc: Array[Int]) extends MyControlMessage
  case class AskControlMessage(message: MyTransition, asker: ActorRef, dest: ActorRef, vc: Array[Int], inspectedTransition: MyTransition, isBlocked: Boolean) extends MyControlMessage
  //inquired transition
  case class TellControlMessage(message: Any, flag: Boolean, teller: ActorRef, dest: ActorRef, vc: Array[Int], inspectedTransition: MyTransition, repRecs: Vector[(MyTransition, Array[Int], String)]) extends MyControlMessage
  case class NotifyControlMessage(asker: ActorRef, vc: Array[Int]) extends MyControlMessage
  case class StashedNormalMessage(message: NormalMessageWithVectorClock) extends StashedMessage
  case class StashedAskMessage(message: AskControlMessage) extends StashedMessage
  //Did not include here to make it transparent
  case class SendOrderMessage(to: ActorRef, message: NormalMessage, automata: Automata)
}


class SecureActor extends Actor{
  import SecureActor._
  val name: String = self.path.name
  val hash: Int = (name.hashCode() % MaxActorNumber).abs
  //there is a 1/100 chance for two strings to have a same hash
  println("my name is " + name + " and my vector clock index is " + hash)
  var vectorClock: Array[Int] = Array.fill(MaxActorNumber)(0)
  var greeting = ""
  var unNotified: Vector[ActorRef] = Vector[ActorRef]()
  //history defined here, i guess this is right, but maybe you need to change it.
  var history: Vector[(MyTransition, Array[Int], String)] = Vector[(MyTransition, Array[Int], String)]()
  var stashNormalQueue: Vector[NormalMessageWithVectorClock] = Vector[NormalMessageWithVectorClock]()
  var stashAskQueue: Vector[AskControlMessage] = Vector[AskControlMessage]()
  var pendingAsk: Map[(MyTransition, Array[Int]), Vector[ActorRef]] = Map()
  // Make it list of control messages
  var pendingMonitorMessage: Vector[(MyControlMessage, MyTransition)] = Vector[(MyControlMessage, MyTransition)]()
  implicit val ec: ExecutionContext = context.dispatcher
  // what about our own clock value if it's bigger in other actor's vc?
  def updateVectorClock(vc: Array[Int]): Unit={
    for(i <- 0 to MaxActorNumber - 1){
      if(vc(i) > vectorClock(i))
        vectorClock(i) = vc(i)
    }
  }

  def vectorClockLess(vc1: Array[Int], vc2: Array[Int]): Boolean={
    for(i <- 0 to MaxActorNumber - 1){
      if(vc1(i) > vc2(i))
        false
    }
    !(vc1 == vc2)
  }

  def vectorClockConcurent(vc1: Array[Int], vc2: Array[Int]): Boolean={
    var hasLess: Boolean = false
    var hasGreater: Boolean = false
    for(i <- 0 to MaxActorNumber - 1){
      if(vc1(i) < vc2(i))
        hasLess = true
      if(vc1(i) > vc2(i))
        hasGreater = true
    }
    (hasLess && hasGreater) || (vc1 == vc2)
  }

  def vectorClockNotGreater(vc1: Array[Int], vc2: Array[Int]): Boolean={
    vectorClockLess(vc1,vc2) || vectorClockConcurent(vc1,vc2)
  }

  def sendNotifications(transitions:Vector[MyTransition], automata: Automata): Unit={
    Thread.sleep(5000)
    var allPres: Vector[MyTransition] = Vector.empty[MyTransition]
    for (transition <- transitions) {
      val pres: Vector[MyTransition] = automata.singleFindPre(transition)
      allPres = allPres ++ pres
    }
    for (pre ← allPres) {
      val msg: MessageBundle = pre.messageBundle
      val notifMsg = NotifyControlMessage(self,vectorClock)
      msg.s ! notifMsg
    }
  }
  // transition status is still not used
  def asynchronizedMonitoring(transitions: Vector[MyTransition], transitionStatus: Vector[Int],  automata: Automata): Unit ={
    //build awaiting tell messages
    for (transition ← transitions) {
      val pendingIndex = (transition, vectorClock)
      var pendingList : Vector[ActorRef] = Vector[ActorRef]()
      val pres: Vector[MyTransition] = automata.singleFindPre(transition)
      for (pre ← pres) {
        val msg: MessageBundle = pre.messageBundle
        val ctrlMsg = AskControlMessage(MyTransition(pre.from, pre.to, msg, true), self, msg.s, vectorClock, transition, false)
        msg.s ! ctrlMsg
        pendingList = pendingList :+ msg.s
      }
      pendingAsk += (pendingIndex -> pendingList)
    }
  }
  def synchronizedMonitoring(transitions: Vector[MyTransition], transitionStatus: Vector[Int],  automata: Automata): Boolean ={
      for (transition ← transitions) {
        val pres: Vector[MyTransition] = automata.singleFindPre(transition)
        var tellList: Vector[Future[TellControlMessage]] = Vector.empty[Future[TellControlMessage]]
        for (pre ← pres) {
          val msg: MessageBundle = pre.messageBundle
          //why construct my transition from scratch? pre is not good enough?
          var isBlocked: Boolean = false
//          if (automata.isLastTransition(pre))
//            isBlocked = true
          val ctrlMsg = AskControlMessage(MyTransition(pre.from, pre.to, msg, true), self, msg.s, vectorClock,transition, true)
          implicit val timeout = Timeout(10.seconds)
          val future: Future[TellControlMessage] = (msg.s ? ctrlMsg).mapTo[TellControlMessage]
          tellList = tellList :+ future
        }
        var preSent: Int = 0
        val all = Future.sequence(tellList)
        Await.result(all, SecureActor.OverallTimeOut)
        for(tellRes <- all.value.get.get){
          if(tellRes.flag == true){
            preSent = preSent + 1
          }
          updateVectorClock(tellRes.vc)
        }
        // in the original problem we should check whether all the messages are sent or not
        if(preSent >= 1){
            if (automata.isLastTransition(transition)) {
               return true
            }
        }
      }
    false
  }

  def sendSecureMessage(receiver: ActorRef, message: NormalMessage, automata: Automata): Unit = {
    // assume that we have the automata in the Actor
    val msgBundle: MessageBundle = new MessageBundle(self, message, receiver)
    //for all transitions
    val transitions = automata.findTransitionByMessageBundle(msgBundle)
    var isLast: Boolean = false
    for(transition <- transitions){
      if (automata.isLastTransition(transition)) {
        isLast = true
      }
    }
    if(isLast)
      sendBlocking(receiver, message, automata, transitions)
    else
      sendNonBlocking(receiver, message, automata, transitions)
  }

  def sendNonBlocking(receiver: ActorRef, message: SecureActor.NormalMessage, automata: Automata, transitions: Vector[MyTransition]): Unit = {
    vectorClock(hash) += 1
    println(self.path.name + " my vector clock value is:" + vectorClock(hash) + " for message: " + message)
    //not used
    val transitionStatus: Vector[Int] = Vector.empty[Int]
    receiver ! NormalMessageWithVectorClock(message.message,vectorClock)
    asynchronizedMonitoring(transitions, transitionStatus, automata)
    addToHistory(transitions, automata)
  }

  def addToHistory(transitions: Vector[MyTransition], automata: Automata): Unit = {
    for (transition <- transitions) {
      val pres: Vector[MyTransition] = automata.singleFindPre(transition)
      if (pres.length == 0 && automata.isLastTransition(transition) == false)
        history = history :+ (transition, vectorClock, "frm")
      else
        history = history :+ (transition, vectorClock, "?")
    }
  }
  //what happens about true, sending the error, or false?
  def relaxedTellCheck(tellMessage: TellControlMessage): Unit = {
    var historyUpdate: Boolean = false
    for(triple <- tellMessage.repRecs){
      //add the vio condition
      if(vectorClockLess(triple._2, tellMessage.vc)){
        for(historyTriple <- history){
          //if(historyTriple._1 == triple._1)
        }
        historyUpdate = true
      }
    }
    if(!historyUpdate){
      for(triple <- tellMessage.repRecs){
        //add the vio condition
        if(vectorClockNotGreater(tellMessage.vc, triple._2) && (vectorClockConcurent(tellMessage.vc, triple._2))){
          for(historyTriple <- history){
            //if(historyTriple._1 == triple._1)
          }
          historyUpdate = true
        }
      }
    }

    //remove transition from history
    //if(!historyUpdate)

  }

  def sendBlocking(receiver: ActorRef, message: NormalMessage, automata: Automata,transitions: Vector[MyTransition] ): Unit = {
    //not used
    val transitionStatus: Vector[Int] = Vector.empty[Int]
    if(synchronizedMonitoring(transitions, transitionStatus, automata)){
      //should send error type message
      vectorClock(hash) += 1
      println(self.path.name + " my vector clock value is:" + vectorClock(hash) + " for message: " + "ERROR")
      receiver ! ErrorMessage(vectorClock)
    }
    else{
      //println("im here to send normal" + " " + self.path)
      addToHistory(transitions, automata)
      vectorClock(hash) += 1
      println(self.path.name + " my vector clock value is:" + vectorClock(hash) + " for message: " + message)
      receiver ! NormalMessageWithVectorClock(message.message,vectorClock)
    }
    sendNotifications(transitions, automata)
  }
  val manageControls : Receive = {
    case SendOrderMessage(to, message, automata) => {
      sendSecureMessage(to, message, automata)
    }
    case ErrorMessage(vc) => {
      updateVectorClock(vc)
      println("Error Message" + " " + self.path.name)
    }

    case NotifyControlMessage(asker,vc) => {
      updateVectorClock(vc)
      println("Notify Message" + " " + self.path.name)
      unNotified = unNotified.filter(_ != asker)
      // i assume that here unnotified just became empty cause we dont get notify message
      // unless we have something in unnotified so if its empty now it's just became empty
      if (unNotified.isEmpty) {
        while (!stashAskQueue.isEmpty) {
          //Here that we have stashed should we change the vector clock of the stashed messages
          //Update: I changed it to be the simpler version and send the old message with the old vc
          self ! stashAskQueue.last
          stashAskQueue = stashAskQueue.init
        }
        while (!stashNormalQueue.isEmpty) {
          self ! stashNormalQueue.last
          stashNormalQueue = stashNormalQueue.init
        }
      }
    }

    case AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked) =>
      updateVectorClock(vc)
      if(unNotified.isEmpty) {
        println("Ask Message " + " " + self.path.name + " " + message.messageBundle.m)
        //val tellControlMessage: TellControlMessage = tellStatusToSender(message.from, message.to, message.messageBundle,message.regTransition,asker)
        val (tellControlMessage,isPending) = tellStatusToSender(AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked))
        if(!isPending)
          sender() ! tellControlMessage
      }
      else {
        val askmsg: AskControlMessage = AskControlMessage(message, asker, dest, vc, inspectedTrans, isBlocked)
        stashAskQueue = stashAskQueue :+ askmsg
      }

    //stashing
    case TellControlMessage(message, flag, teller, dest, vc, transition, repRecs) =>
      println("I CAUGHT A TELL CONTROL MESSAGE")
      for((pending, actorList) <- pendingAsk){
        if(pending._1 == message && pending._2(hash) == vc(hash)){
          actorList filterNot teller.==
          //if actorList.length == 0 ?
        }
      }
    case NormalMessageWithVectorClock(message,vc) =>
      //increment vectorClock
      updateVectorClock(vc)
      if(unNotified.isEmpty) {
        //here we don't send
        self ! message
      } else
        stashNormalQueue = stashNormalQueue :+ NormalMessageWithVectorClock(message,vc)
  }

//  val manageNormals: Receive = {
//
//  }

  //user should write it
  def receive = manageControls//.orElse(manageNormals)

  def tellStatusToSender(message: AskControlMessage): (TellControlMessage, Boolean) ={
          var res:  Vector[(MyTransition, Array[Int], String)] = Vector()
          var isPending: Boolean = false
          var answer = true
          answer = false
          for(triple <- history){
            if(triple._1 == message.message){
              if(triple._3 == "?") {
                if (vectorClockLess(triple._2, message.vc)) {
                  pendingMonitorMessage = pendingMonitorMessage :+ (message, triple._1)
                  isPending = true
                }
              }
            }
          }

          if(isPending == false){
            for(triple <- history){
              if(triple._1 == message.message){
                if(triple._3 == "?"){
                  if(vectorClockConcurent(triple._2, message.vc))
                    res = res :+ (message.message, triple._2, "frmP")
                }
                else if(vectorClockNotGreater(triple._2, message.vc))
                  res = res :+ (message.message, triple._2, triple._3)
              }
            }
          }
          if (!isPending) {
            if (message.isBlocked)
              unNotified = unNotified :+ message.asker
          }
          //TODO check history and automata, send a tell message to sender with msg and true/false, sender will erase that msg from his "Pres set"
          (TellControlMessage(message.message, true, self, message.asker, vectorClock, message.inspectedTransition, res), isPending)
  }
}

  //  def tellStatusToSender(from: Int, to: Int, messageBundle: MessageBundle, regTransiton: Boolean, asker: ActorRef): TellControlMessage ={
//          var answer = true
//          val transition: MyTransition = MyTransition(from, to, messageBundle, regTransiton)
//          answer = false
//          for(triple <- history){
//            if(triple._1 == transition){
//              answer = true
//            }
//          }
//          val tellControlMessage: TellControlMessage = TellControlMessage(transition, answer, vectorClock)
//          //TODO check history and automata, send a tell message to sender with msg and true/false, sender will erase that msg from his "Pres set"
//          //assumed that tell does just like !
//          println(tellControlMessage)
//          // could be asker ! tellControlMessage
//          unNotified = unNotified :+ asker
//          tellControlMessage
//  }
//}


object MainApp extends App {
  import SecureActor._
  val system: ActorSystem = ActorSystem("helloAkka")
  val firstActor: ActorRef =
    system.actorOf(SecureActor.props().withDispatcher("custom-dispatcher"),"firstActor")
  val secondActor: ActorRef =
    system.actorOf(SecureActor.props().withDispatcher("custom-dispatcher"),"secondActor")
  val customAutomata: Automata = new Automata
  val customBundle: MessageBundle = new MessageBundle(secondActor, NormalMessage("a0"), firstActor)
  val customTransition: MyTransition = MyTransition(0,1, customBundle ,true)
  val customBundle2: MessageBundle = new MessageBundle(firstActor, NormalMessage("b1"),secondActor)
  val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
  customAutomata.addTransition(customTransition)
  customAutomata.addTransition(customTransition2)
  customAutomata.addLastTransition(2)
  secondActor ! SendOrderMessage(firstActor, NormalMessage("a0"), customAutomata)
  secondActor ! SendOrderMessage(secondActor, NormalMessage("c2"), customAutomata)
  firstActor ! SendOrderMessage(secondActor, NormalMessage("b1"), customAutomata)
  secondActor ! SendOrderMessage(secondActor, NormalMessage("c1"), customAutomata)
}
