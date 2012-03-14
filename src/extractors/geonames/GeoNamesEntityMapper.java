package extractors.geonames;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javatools.administrative.Announce;
import javatools.datatypes.FinalSet;
import javatools.filehandlers.FileLines;
import basics.Fact;
import basics.FactComponent;
import basics.FactSource;
import basics.FactWriter;
import basics.Theme;
import extractors.Extractor;
import extractors.InfoboxExtractor;

/**
 * The GeoNamesEntityMapper maps geonames entities to Wikipedia entities.
 * 
 * Needs the GeoNames allCountries.txt as input.
 * 
 * @author Johannes Hoffart
 *
 */
public class GeoNamesEntityMapper extends Extractor {

  private File allCountries;
  
  private Map<String, List<Integer>> name2ids = new HashMap<>();
  private Map<Integer, Float> id2latitude = new HashMap<>();
  private Map<Integer, Float> id2longitude = new HashMap<>();
  
  /** geonames entity links */
  public static final Theme GEONAMESENTITYIDS = new Theme("geonamesEntityIds", "IDs from GeoNames entities");

  @Override
  public Set<Theme> input() {
    return new HashSet<Theme>(Arrays.asList(
        InfoboxExtractor.DIRTYINFOBOXFACTS));
  }

  @Override
  public Set<Theme> output() {
    return new FinalSet<Theme>(GEONAMESENTITYIDS);
  }

  @Override
  public void extract(Map<Theme, FactWriter> output, Map<Theme, FactSource> input) throws Exception {    
    for (String line : new FileLines(allCountries, "UTF-8", "Reading GeoNames entities")) {
      String[] data = line.split("\t");

      Integer geonamesId = Integer.parseInt(data[0]);
      String name = data[1];

      List<Integer> ids = name2ids.get(name);
      if (ids == null) {
        ids = new LinkedList<Integer>();
        name2ids.put(name, ids);
      }
      ids.add(geonamesId);
      
      Float lati = Float.parseFloat(data[4]);
      Float longi = Float.parseFloat(data[5]);      
      
      id2latitude.put(geonamesId, lati);
      id2longitude.put(geonamesId, longi);
    }

    FactSource ibFacts = input.get(InfoboxExtractor.DIRTYINFOBOXFACTS);
       
    Map<String, Float> halfCoordinateCache = new HashMap<String, Float>();
    
    for (Fact f : ibFacts) {
      if (f.getRelation().equals("<hasLatitude>") || f.getRelation().equals("<hasLongitude>")) {
        String location = f.getArg(1);
        
        Float halfCoordinate = halfCoordinateCache.get(location);
        if (halfCoordinate == null) {
          halfCoordinateCache.put(location, 
              Float.parseFloat(FactComponent.stripQuotes(FactComponent.literalAndDataType(f.getArg(2))[0])));
          continue;
        }
        
        // both coordinates are available, attemp matching
        Float lats = null;
        Float longs = null;
        
        switch (f.getRelation()) {
          case "<hasLatitude>":
            lats = Float.parseFloat(FactComponent.stripQuotes(FactComponent.literalAndDataType(f.getArg(2))[0]));
            longs = halfCoordinateCache.get(location);            
            break;

          case "<hasLongitude>":
            lats = halfCoordinateCache.get(location);            
            longs = Float.parseFloat(FactComponent.stripQuotes(FactComponent.literalAndDataType(f.getArg(2))[0]));
            break;
        }
        
        Integer geoId = matchToGeonames(location, lats, longs);
        
        if (geoId != -1) {
          output.get(GEONAMESENTITYIDS).write(new Fact(location, "<hasGeonamesEntityId>", FactComponent.forNumber(geoId)));
        }
      }
    }
  }
  
  public Integer matchToGeonames(String name, Float lats, Float longs) {
    if (isInGeonames(name)) {
      List<Integer> possibleLocations = getGeonamesIds(name);

      Integer correctLocation = -1;

      if (possibleLocations.size() == 1) {
        correctLocation = possibleLocations.get(0);
      } else if (possibleLocations.size() > 1) {
        // try to disambiguate by choosing the closest location
        if (lats != null && longs != null) {
          correctLocation = getIdForLocation(name, lats, longs);

          if (correctLocation != -1) {
            Announce.debug("Found geonames target using disambiguation");
          }
        }
      }

      if (correctLocation != -1) {
        // update geonames db to store the mapping - 
        // this allows to retrieve YAGO entities by geonames id later
        // in the GeonamesImporter - this is also where all the facts
        // will be added to the entity
        return correctLocation;
      }
    }
    
    return -1;
  }
  
  private boolean isInGeonames(String locationName) {
    return name2ids.containsKey(locationName);
  }

  private List<Integer> getGeonamesIds(String locationName) {
    return name2ids.get(locationName);
  }

  private int getIdForLocation(String locationName, float latitude, float longitude) {
    List<Integer> possibleLocations = getGeonamesIds(locationName);

    // assume there is only one correct place for any given name/coord pair
    for (int possibleLocation : possibleLocations) {
      if (isNearby(possibleLocation, latitude, longitude)) {
        return possibleLocation;
      }
    }

    // nothing was found with fitting name/coordinates
    return -1;
  }
  
  private boolean isNearby(int possibleLocation, float latitude, float longitude) {
    float cLat = id2latitude.get(possibleLocation);
    float cLong = id2longitude.get(possibleLocation);

    if (distance(cLat, cLong, latitude, longitude) < 0.05) {
      return true;
    } else {
      return false;
    }
  }
  
  /**
   * Returns the angle distance between coordinate A and B
   * in Degrees. 0.01 degrees is roughly equivalent to 1.11 km (depending 
   * on the location on Earth. This should be enough to discriminate.
   * 
   * @param latA
   * @param longA
   * @param latB
   * @param longB
   * @return Distance between A and B in degrees
   */
  private double distance(float latA, float longA, float latB, float longB) {
    double lat1 = Math.toRadians(new Double(latA));
    double long1 = Math.toRadians(new Double(longA));
    double lat2 = Math.toRadians(new Double(latB));
    double long2 = Math.toRadians(new Double(longB));

    double angleDistance = Math.toDegrees(Math.acos(Math.sin(lat1) * Math.sin(lat2) + Math.cos(lat1) * Math.cos(lat2) * Math.cos(long1 - long2)));

    return angleDistance;
  }

  public GeoNamesEntityMapper(File allCountries) {
    this.allCountries = allCountries;
  }
}
