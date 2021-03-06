/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package server;

import bank.IBank;
import client.IMarketCommunicator;
import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



/**
 *
 * @author Fernando Garcia Sanjuan, <fgs@kth.se>, <fdosanjuan@gmail.com>
 */
public class Marketplace
   extends UnicastRemoteObject
   implements IMarketplace
{
   static final String NAME = "rmi://localhost/marketplace";
   IBank bank;
   Map<String, IMarketCommunicator> customers = new HashMap<String, IMarketCommunicator>();
   List<Item> items = new ArrayList<Item>();
   List<Item> wishes = new ArrayList<Item>();
   
   
   public Marketplace(String bankName)
      throws RemoteException
   {
      super();
      
      try
      {
         try
         {
            bank = (IBank) Naming.lookup(bankName);
         }
         catch (Exception ex)
         {
            System.err.println("Error looking for the bank given the URL: " + 
                               bankName);
            System.exit(1);
         }
         
         Naming.rebind(NAME, this);
         System.out.println("Server ready.");
      }
      catch (MalformedURLException ex)
      {
         System.err.println("Error registering the object Marketplace.");
         System.exit(1);
      }
   }


   @Override
   public synchronized void registerClient(IMarketCommunicator com)
      throws RemoteException
   {
      customers.put(com.getName(), com);
      System.out.println("Registering customer: " + com.getName());
      
      System.out.println("Sending items list to the new customer.");
      
      updateCustomerItemList(com);
      updateCustomerWishList(com);
   }
   
   
   @Override
   public synchronized void unregisterClient(IMarketCommunicator com)
      throws RemoteException
   {
      customers.remove(com.getName());
      System.out.println("Unregistering customer: " + com.getName());
   }


   @Override
   public synchronized void addItem(String name, float price, String ownerId)
      throws RemoteException
   {
      Item newItem = new Item(name, price, ownerId);
      items.add(newItem);
      
      System.out.println("Adding new item from " + ownerId + " for sale: " + 
                         name + " - " + price + " SEK.");
      
      updateCustomersItemLists();
      
      manageWishes();
   }


   @Override
   public synchronized void addWish(String name, float price, String customerId)
      throws RemoteException
   {
      Item newWish = new Item(name, price, customerId);
      wishes.add(newWish);
      
      System.out.println("Adding new wish from " + customerId + ": " + 
                         name + " - " + price + " SEK.");
      
      IMarketCommunicator customer = customers.get(customerId);
      updateCustomerWishList(customer);
      
      manageWishes();
   }


   @Override
   public synchronized boolean buyItem(String name, float price, String customerId)
      throws RemoteException
   {
      Item selected = findItem(name, price);
      
      if (selected == null)
      {
         return false;
      }
      else
      {
         String sellerName = selected.getOwner();
         
         boolean result = makePurchase(selected, customerId, sellerName);
         if (result == true)
            manageWishes();
         return result;
      }
   }


   private synchronized ArrayList<Item> getWishList(String ownerId)
   {
      ArrayList<Item> ret = new ArrayList<Item>();
      
      for (Item wish : wishes)
      {
         if (wish.getOwner().equals(ownerId))
            ret.add(wish);
      }
      
      return ret;
   }
   
   
   private synchronized Item findItem(String name, float price)
   {
      for (Item it : items)
      {
         if ((it.getName().equals(name)) && (it.getPrice() == price))
         {
            return it;
         }
      }
      
      return null;
   }
   
   
   private synchronized void updateCustomersItemLists()
   {
      System.out.println("Updating customers' lists of items.");
      
      for (IMarketCommunicator cust : customers.values())
      {
         updateCustomerItemList(cust);
      }
   }
   
   
   private synchronized void updateCustomerItemList(IMarketCommunicator cust)
   {
      try
      {
         cust.updateMarketList(getItemNames());
      }
      catch (RemoteException ex)
      {
         System.err.println("Error updating a customer's list of items.");
      }
   }
   
   
   private synchronized void updateCustomerWishList(IMarketCommunicator customer)
   {
      try
      {
         String custName = customer.getName();
         
         System.out.println("Sending list of wishes to the customer: " + custName);
         
         customer.updateWishList(getWishNames(custName));
      }
      catch (RemoteException ex)
      {
         System.err.println("Error updating a customer's list of wishes.");
      }
   }
   
   
   // Inefficient. O(n^2)
   private synchronized void manageWishes()
   {
      IMarketCommunicator buyer = null;
      boolean any = false;
      
      ArrayList<Item> newsWishes = new ArrayList<Item>();
      
      for (int i = 0; i < wishes.size(); ++i)
      {
         Item wish = wishes.get(i);
         newsWishes.add(wish);
         
         List<Item> workingItems = items;
         
         for (int j = 0; j < workingItems.size(); ++j)
         {
            buyer = null;
            Item item = workingItems.get(j);
            
            if (item.getName().equals(wish.getName()))
            {
               if (item.getPrice() <= wish.getPrice())
               {
                  boolean ok = makePurchase(item, wish.getOwner(), item.getOwner());
                  if (!ok)
                  {
                     System.err.println("Error making the purchase of a wish.");
                  }
                  else
                  {
                     buyer = customers.get(wish.getOwner());
                     if (buyer == null)
                     {
                        System.err.println("Error updating buyer wish list.");
                     }
                     else
                     {
                        newsWishes.remove(wish);
                        any = true;
                        break;
                     }
                  }
               }
            }
         }
      }
      
      if (any)
      {
         wishes = newsWishes;
         updateCustomerWishList(buyer);
      }
   }


   private synchronized boolean makePurchase(Item item, String customerName, String sellerName)
   {
      IMarketCommunicator buyer = customers.get(customerName);
      IMarketCommunicator seller = customers.get(sellerName);

      if (buyer == null || seller == null)
      {
         return false;
      }
      else
      {
         try
         {
            float price = item.getPrice();
            
            bank.getAccount(customerName).withdraw(price);
            buyer.notifyOfPurchase(item.getName(), item.getPrice());

            bank.getAccount(sellerName).deposit(price);
            seller.notifyOfSale(item.getName(), item.getPrice());

            items.remove(item);
            updateCustomersItemLists();
         }
         catch (Exception ex)
         {
            return false;
         }
      }
      
      System.out.println("Purchase made from " + customerName);
      
      return true;
   }
   
   
   private synchronized ArrayList<String> getWishNames(String customer)
   {
      ArrayList<Item> wishList = getWishList(customer);
      ArrayList<String> ret = new ArrayList<String>();
         
      for (Item w : wishList)
         ret.add(w.toString());
      
      return ret;
   }
   
   
   private synchronized ArrayList<String> getItemNames()
   {
      ArrayList<String> ret = new ArrayList<String>();
      
      for (Item i : items)
         ret.add(i.toString());
      
      return ret;
   }
}
