/*
        Copyright 2011 Spiral Arm Ltd

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.package bootstrap.liftmodules
*/
package net.liftmodules.imapidle

import javax.mail._
import javax.mail.event._
import javax.mail.internet._
import com.sun.mail.imap._

import java.util.Properties

import net.liftweb.actor._
import net.liftweb.common._
import net.liftweb.util._

case class Credentials(username: String, password: String, host: String = "imap.gmail.com")
case class Callback(h: MessageHandler)


object EmailReceiver extends LiftActor with Loggable {

  private var inbox: Box[Folder] = Empty
  private var store: Box[Store] = Empty

  private var credentials: Box[Credentials] = Empty
  private var callback: Box[MessageHandler] = Empty

  // To be able to remove listeners during a clean up, we need to keep a list of them.
  private var listeners: List[java.util.EventListener] = Nil

  // Idle the connection. "Idle" in the sense of "run slowly while disconnected from a load or out of gear" perhaps. RFC2177
  def idle {

    // IMAPFolder.idle() blocks until the server has an event for us, so we call this in a separate thread. 
    def safeIdle(f: IMAPFolder) {
      scala.actors.Actor.actor {
        try {
          logger.debug("IMAP Actor idle block entered")
          f.idle
          logger.debug("IMAP Actor idle block exited")
        } catch { // If the idle fails, we want to restart the connection because we will no longer be waiting for messages.
          case x =>
            logger.warn("IMAP Attempt to idle produced " + x)
            EmailReceiver ! 'restart
        }
      }
    }

    inbox match {
      case Full(f: IMAPFolder) => safeIdle(f)
      case x => logger.error("IMAP Can't idle " + x)
    }
  }

  // Connect to GMAIL and pipe all events to this actor
  private def connect = {

    logger.debug("IMAP Connecting")
    require(credentials.isEmpty == false)
	require(callback.isEmpty == false)

    val props = new Properties
    props.put("mail.store.protocol", "imaps")
    props.put("mail.imap.enableimapevents", "true")

    val session = Session.getDefaultInstance(props)

    session.setDebug(Props.getBool("mail.session.debug", true))

    val store = session.getStore()

    val connectionListener = new ConnectionListener {
      def opened(e: ConnectionEvent): Unit = EmailReceiver ! e
      def closed(e: ConnectionEvent): Unit = EmailReceiver ! e
      def disconnected(e: ConnectionEvent): Unit = EmailReceiver ! e
    }
    listeners = connectionListener :: Nil
    store.addConnectionListener(connectionListener)

    // We may be able to live without store event listeners as they only seem to be notices.
    val storeListener = new StoreListener {
      def notification(e: StoreEvent): Unit = EmailReceiver ! e
    }
    listeners = storeListener :: listeners
    store.addStoreListener(storeListener)

	credentials foreach { c => 
    	store.connect(c.host, c.username, c.password)

    	val inbox = store.getFolder("INBOX")
    	inbox.open(Folder.READ_WRITE)

    	val countListener = new MessageCountAdapter {
      		override def messagesAdded(e: MessageCountEvent): Unit = EmailReceiver ! e
    	}
    	inbox.addMessageCountListener(countListener)
    	listeners = countListener :: listeners

    	logger.info("IMAP Connected, listeners ready")

    	this.inbox = Full(inbox)
    	this.store = Full(store)

    	idle
	}
  }

  private def disconnect {

    logger.debug("IMAP Disconnecting")

    // We un-bind the listeners before closing to prevent us receiving disconnect messages
    // which... would make us want to reconnect again.

    inbox foreach { i =>
      listeners foreach {
        case mcl: MessageCountListener => i.removeMessageCountListener(mcl)
        case _ =>
      }
      if (i.isOpen) i.close(true)
    }

    store foreach { s =>
      listeners foreach {
        case cl: ConnectionListener => s.removeConnectionListener(cl)
        case sl: StoreListener => s.removeStoreListener(sl)
        case _ =>
      }
      if (s.isConnected) s.close()
    }

    inbox = Empty
    store = Empty
    listeners = Nil

  }

  private def retry(attempts_left: Int)(block: => Unit): Unit = attempts_left match {
    case 0 => logger.error("IMAP Ran out of retry attempts - check log for root cause")
    case n =>
      try {
        block
      } catch {
        case e =>
          logger.warn("IMAP Retry failed - will retry", e)
          Thread.sleep(1000L * 60)
          retry(n - 1)(block)
      }
  }

  private def reconnect {
    disconnect
    Thread.sleep(1000L * 5)
    connect
  }

  private def processEmail(messages: Array[Message]) {

	for (m <- messages; c <- callback; if c(m)) {
		 m.setFlag(Flags.Flag.DELETED, true)
	}
	
	inbox foreach { _.expunge }
  }
  
  // Useful for debugging from the console:
  //def getInbox = inbox

  def messageHandler = {

    case c: Credentials => credentials = Full(c)

	case Callback(h) => callback = Full(h)

    case 'startup => connect

    case 'shutdown => disconnect

    case 'restart =>
      logger.info("IMAP Restart request received")
      retry(10) {
        reconnect
      }

    case 'collect =>
      logger.info("IMAP Manually checking inbox")
      inbox map { _.getMessages } foreach { msgs => processEmail(msgs) }
      idle

    case e: MessageCountEvent if !e.isRemoved =>
      processEmail(e.getMessages)
      idle

    case e: StoreEvent => logger.warn("IMAP Store event reported: " + e.getMessage)

    case e: ConnectionEvent if e.getType == ConnectionEvent.OPENED => logger.debug("IMAP Connection opened")
    case e: ConnectionEvent if e.getType == ConnectionEvent.DISCONNECTED => logger.warn("IMAP Connection disconnected")

    case e: ConnectionEvent if e.getType == ConnectionEvent.CLOSED =>
      logger.info("IMAP Connection closed - reconnecting")
      reconnect

    case e => logger.warn("IMAP Unhandled email event: " + e)
  }
}
