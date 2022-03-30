package tel.discord.rtab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.internal.utils.tuple.MutablePair;


public class Player
{
	static final int MAX_BOOSTER = 999;
	static final int MIN_BOOSTER =  10;
	final static int REQUIRED_STREAK_FOR_BONUS = 40;
	GameController game;
	public User user;
	public Member member;
	private String name;
	public String uID;
	Instant lifeRefillTime;
	public boolean paidLifePenalty = false;
	public int money;
	int oldMoney;
	int originalMoney;
	int currentCashClub;
	public int booster;
	public int oneshotBooster;
	public int winstreak;
	//Event fields
	public int peeks;
	public int jokers;
	public int boostCharge;
	public int jackpot;
	public boolean splitAndShare;
	public boolean minigameLock;
	//In-game variables
	boolean threshold;
	boolean warned;
	public PlayerStatus status;
	public LinkedList<Integer> knownBombs;
	public LinkedList<Integer> safePeeks;
	LinkedList<MutablePair<Integer,Integer>> annuities;
	//Constructor for humans
	public Player(Member playerName, GameController game, String botName)
	{
		user = playerName.getUser();
		member = playerName;
		uID = user.getId();
		name = playerName.getEffectiveName();
		initPlayer(game);
	}
	
	void initPlayer(GameController game)
	{
		this.game = game;
		lifeRefillTime = Instant.now().plusSeconds(72000);
		money = 0;
		booster = 100;
		winstreak = 10;
		peeks = 1;
		jokers = 0;
		boostCharge = 0;
		splitAndShare = false;
		minigameLock = false;
		threshold = false;
		warned = false;
		status = PlayerStatus.OUT;
		knownBombs = new LinkedList<>();
		safePeeks = new LinkedList<>();
		annuities = new LinkedList<>();
		List<String> list;
		try
		{
			list = Files.readAllLines(Paths.get("scores","scores"+game.channel.getId()+".csv"));
		}
		catch(IOException e)
		{
			System.out.println("No savefile found for "+game.channel.getName()+", creating.");
			list = new LinkedList<String>();
			try
			{
				Files.createFile(Paths.get("scores","scores"+game.channel.getId()+".csv"));
			}
			catch (IOException e1)
			{
				System.err.println("Couldn't create it either. Oops.");
				e1.printStackTrace();
				return;
			}
		}
		String[] record;
		for(int i=0; i<list.size(); i++)
		{
			/*
			 * record format:
			 * record[0] = uID
			 * record[1] = name
			 * record[2] = money
			 * record[3] = booster
			 * record[4] = winstreak
			 * record[5] = newbieProtection
			 * record[6] = lives
			 * record[7] = time at which lives refill
			 * record[8] = saved hidden command
			 * record[9] = saved boost charge
			 * record[10] = annuities
			 * record[11] = total lives spent
			 * record[12] = list of enhanced games
			 */
			record = list.get(i).split("#");
			if(record[0].equals(uID))
			{
				money = Integer.parseInt(record[2]);
				booster = Integer.parseInt(record[3]);
				winstreak = Integer.parseInt(record[4]);
				lifeRefillTime = Instant.parse(record[7]);
				boostCharge = Integer.parseInt(record[9]);
				//The annuities structure is more complicated, we can't just parse it in directly like the others
				String savedAnnuities = record[10];
				savedAnnuities = savedAnnuities.replaceAll("[^\\d,-]", "");
				String[] annuityList = savedAnnuities.split(",");
				for(int j=1; j<annuityList.length; j+=2)
					annuities.add(MutablePair.of(Integer.parseInt(annuityList[j-1]), Integer.parseInt(annuityList[j])));
				//Then enhanced game list is somewhat similar
				break;
			}
		}
		oldMoney = money;
		originalMoney = money;
		currentCashClub = money/100_000_000;
	}
	int giveAnnuities()
	{
		int totalPayout = 0;
		//Run through the iterator and tally up the payments
		ListIterator<MutablePair<Integer, Integer>> iterator = annuities.listIterator();
		while(iterator.hasNext())
		{
			MutablePair<Integer,Integer> nextAnnuity = iterator.next();
			totalPayout += nextAnnuity.getLeft();
			//If it's a temporary annuity, reduce the duration by one and remove it if it's gone, or update it if it's not
			if(nextAnnuity.getRight() > 0)
			{
				nextAnnuity.setRight(nextAnnuity.getRight()-1);
				if(nextAnnuity.getRight() == 0)
					iterator.remove();
				else
					iterator.set(nextAnnuity);
			}
		}
		//Then return the total amount to be paid
		return totalPayout;
	}
	public StringBuilder addMoney(int amount, MoneyMultipliersToUse multipliers)
	{
		//Start with the base amount
		long adjustedPrize = calculateBoostedAmount(amount,multipliers);
		//Dodge overflow by capping at +-$1,000,000,000 while adding the money
		if(adjustedPrize + money > 1_000_000_000) money = 1_000_000_000;
		else if(adjustedPrize + money < -1_000_000_000) money = -1_000_000_000;
		else money += adjustedPrize;
		//Build the string if we need it
		if(adjustedPrize != amount)
		{
			StringBuilder resultString = new StringBuilder();
			resultString.append("...which gets ");
			resultString.append(Math.abs(adjustedPrize) < Math.abs(amount) ? "drained" : "boosted");
			resultString.append(" to **");
			if(adjustedPrize<0)
				resultString.append("-");
			resultString.append("$");
			resultString.append(String.format("%,d**",Math.abs(adjustedPrize)));
			resultString.append(adjustedPrize<amount ? "." : "!");
			return resultString;
		}
		return null;
	}
	public void addBooster(int amount)
	{
		booster += amount;
		//Convert excess boost to cash
		int excessBoost = 0;
		if(booster > MAX_BOOSTER)
		{
			excessBoost = game.applyBaseMultiplier(10000) * (booster - MAX_BOOSTER);
			addMoney(excessBoost, MoneyMultipliersToUse.NOTHING);
			game.channel.sendMessage(String.format("Excess boost converted to **$%,d**!",excessBoost)).queue();
			booster = MAX_BOOSTER;
		}
		if(booster < MIN_BOOSTER)
		{
			excessBoost = game.applyBaseMultiplier(10000) * (booster - MIN_BOOSTER);
			addMoney(excessBoost, MoneyMultipliersToUse.NOTHING);
			booster = MIN_BOOSTER;
			game.channel.sendMessage(String.format("Excess boost converted to **-$%,d**.",Math.abs(excessBoost))).queue();
		}
	}
	public int calculateBoostedAmount(int amount, MoneyMultipliersToUse multipliers)
	{
		long adjustedPrize = amount;
		//Boost and bonus don't stack - if both are permitted, only use whichever is greater
		if((multipliers.useBoost && !multipliers.useBonus) || (multipliers.useBoost && booster * oneshotBooster >= winstreak*10))
		{
			//Multiply by the booster (then divide by 100 since it's a percentage)
			adjustedPrize *= booster;
			adjustedPrize /= 100;
			//Apply their oneshot booster as well
			adjustedPrize *= oneshotBooster;
			oneshotBooster = 1;
		}
		//And if it's a "bonus" (win bonus, minigames, the like), multiply by winstreak ("bonus multiplier") too
		//But make sure they still get something even if they're on x0
		if((multipliers.useBonus && !multipliers.useBoost) || (multipliers.useBonus && winstreak*10 > booster))
		{
			adjustedPrize *= Math.max(10,winstreak);
			adjustedPrize /= 10;
		}
		if(adjustedPrize > 1_000_000_000)
			adjustedPrize = 1_000_000_000;
		if(adjustedPrize < -1_000_000_000)
			adjustedPrize = -1_000_000_000;
		return (int)adjustedPrize;
	}
	public int addAnnuity(int annuityAmount, int timePeriod)
	{
		int boostedAmount = calculateBoostedAmount(annuityAmount, MoneyMultipliersToUse.BOOSTER_OR_BONUS);
		annuities.add(new MutablePair<Integer, Integer>(boostedAmount, timePeriod));
		return boostedAmount;
	}
	public void addWinstreak(int streakAmount)
	{
		winstreak += streakAmount;
	}
	public StringBuilder blowUp(int penalty, boolean holdLoot)
	{
		//Start with modifiers the main controller needs
		game.repeatTurn = 0;
		game.playersAlive --;
		status = PlayerStatus.OUT;
		//Bomb penalty needs to happen before resetting their booster
		if(threshold)
		{
			penalty *= 4;
			if(penalty != 0)
				game.channel.sendMessage(String.format("Threshold Situation: Penalty multiplied to **$%,d**.",Math.abs(penalty))).queue();
		}
		StringBuilder output = addMoney(penalty,MoneyMultipliersToUse.BOOSTER_ONLY);
		//If they've got a split and share, they're in for a bad time
		if(splitAndShare)
		{
			game.channel.sendMessage("Because " + getSafeMention() + " had a split and share, "
					+ "2% of their total will be given to each living player.")
					.queueAfter(1,TimeUnit.SECONDS);
			int moneyLost = (int)(money/50);
			addMoney(-1*moneyLost*game.playersAlive,MoneyMultipliersToUse.NOTHING);
			//Pass the money back to other living players
			for(Player nextPlayer : game.players)
				if(nextPlayer.status == PlayerStatus.ALIVE)
				{
					nextPlayer.addMoney(moneyLost,MoneyMultipliersToUse.NOTHING);
				}
		}
		//Wipe their booster if they didn't hit a boost holder
		if(!holdLoot)
		{
			booster = 100;
			boostCharge = 0;
		}
		//Wipe everything else too, and dock them a life
		winstreak = 10;
		peeks = 0;
		//Dumb easter egg
		if(money <= -1000000000)
		{
			game.channel.sendMessage("I'm impressed, "
					+ "but no you don't get anything special for getting your score this low.").queue();
			game.channel.sendMessage("See you next season!").queueAfter(1,TimeUnit.SECONDS);
		}
		return output;
	}
	
	/*
	 * If the player is human, gets their name as a mention
	 * If they aren't, just gets their name because user = null and null pointers are bad news bears yo!
	 */
	public String getSafeMention()
	{
		return user.getAsMention();
	}
	
	public int getRoundDelta()
	{
		return money - oldMoney;
	}
	public int resetRoundDelta()
	{
		int amountLost = money - oldMoney;
		money = oldMoney;
		return amountLost;
	}
	
	public String printBombs()
	{
		StringBuilder result = new StringBuilder();
		result.append(name);
		result.append(":");
		for(int bomb : knownBombs)
		{
			result.append(" ");
			result.append(String.format("%02d",bomb+1));
		}
		return result.toString();
	}
	
	public String getName()
	{
		return name;
	}
}