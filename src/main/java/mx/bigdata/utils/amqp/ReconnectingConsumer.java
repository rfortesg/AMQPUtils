/*
 *  Copyright 2010 BigData Mx
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package mx.bigdata.utils.amqp;

import java.io.IOException;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.ShutdownSignalException;

import java.util.logging.Level;
import java.util.logging.Logger;

import mx.bigdata.utils.amqp.AMQPClientHelper;

public abstract class ReconnectingConsumer {  
  
  private final static int MAX_BACKOFF = 32*1000;
  
  private final Logger logger = Logger.getLogger(getClass().getName());

  private final ConnectionFactory factory;

  protected final String tag;

  protected final String key;

  protected final AMQPClientHelper amqp;

  private DefaultConsumer consumer;

  private String consumerTag;

  public ReconnectingConsumer(String tag, String key, AMQPClientHelper amqp, 
			      ConnectionFactory factory) {
    this.tag = tag;
    this.key = key;
    this.amqp = amqp;
    this.factory = factory;
    initConsumer();
  }

  public ReconnectingConsumer(String tag, String key, AMQPClientHelper amqp) 
    throws Exception {
    this(tag, key, amqp, amqp.createConnectionFactory(key));
  }

  protected abstract void handleDelivery(String consumerTag, Envelope envelope,
					 AMQP.BasicProperties properties,
					 byte[] body) throws IOException;

  protected abstract String createQueue(AMQPClientHelper amqp, Channel channel,
					String key) throws Exception;

  private boolean initConsumer() {
    Channel channel = null;
    try {
      channel = amqp.declareChannel(factory, key);
      String queue = createQueue(amqp, channel, key);
      this.consumer = 
	new DefaultConsumer(channel) {
	  
	  @Override
	  public void handleDelivery(String consumerTag,
				     Envelope envelope,
				     AMQP.BasicProperties properties,
				     byte[] body)
	    throws IOException {
	    // try {
	    //   ReconnectingConsumer.this
	    // 	.handleDelivery(consumerTag, envelope, properties, body);
	    // } catch (IOException ex) {
	    //   this.getChannel().basicReject(envelope.getDeliveryTag(), false);
	    // }
	    ReconnectingConsumer.this
	      .handleDelivery(consumerTag, envelope, properties, body);
	  }

	  @Override
	  public void handleConsumeOk(String consumerTag) {
	    ReconnectingConsumer.this.consumerTag = consumerTag;
	  }
	  
	  @Override
	  public void handleCancel(String consumerTag) throws IOException {
	    logger.warning("handleCancel for consumer tag: " + consumerTag);
	    try { 
	      this.getChannel()
		.basicCancel(ReconnectingConsumer.this.consumerTag); 
	    } catch(Exception ignore) { }
	    try { 
	      this.getChannel().getConnection().close();
	    } catch(Exception ignore) { }
	    reconnect();
	  }
	  
	  @Override
	  public void handleShutdownSignal(java.lang.String consumerTag,
					   ShutdownSignalException sig) {
	    logger.log(Level.WARNING, "ShutdownSignal for tag: " + tag
		       + "\n\t consumer tag: " + consumerTag 
		       + "\n\t reason: " + sig.getReason() 
		       + "\n\t reference: " + sig.getReason(), sig);
	    try { 
	      this.getChannel()
		.basicCancel(ReconnectingConsumer.this.consumerTag); 
	    } catch(Exception ignore) { }
	    try {
	      this.getChannel().getConnection().close();
	    } catch(Exception ignore) { }
	    reconnect();
	  }
	};

      channel.basicConsume(queue, false, consumer);
      logger.info("Consumer " + tag + " initilized");
      return true;
    } catch (Throwable e) {
      logger.fine("Exception initializing consumer " + tag + ": " + e);
      if (channel != null) {
	try {
	  channel.getConnection().close();
	} catch (Exception ingore) { }
      }
    } 
    return false;
  }

  protected Channel getChannel() {
    return consumer.getChannel();
  }

  protected Channel channel() {
    return consumer.getChannel();
  }

  private void reconnect() {
    reconnect(0, 1);
  }

  private void reconnect(int backoff, int pow) {
    try {
      if (backoff > 0) {
	logger.info("Reconnecting consumer " + tag + " in " 
	 	    + (backoff / 1000) + " seconds ");
	Thread.sleep(backoff);
      }
    } catch (InterruptedException ignore) { }
    boolean initialized = initConsumer();
    if (!initialized) {
      if (backoff >= MAX_BACKOFF) {
	backoff = MAX_BACKOFF;
      } else {
	backoff = (int) (Math.pow(2, pow));
	pow += 1;
      }
      reconnect(backoff, pow);
    }
  }
}