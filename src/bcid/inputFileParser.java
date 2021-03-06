package bcid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.timer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.StringTokenizer;

/**
 * Parse an input File and construct an element Iterator which can be fetched
 */
public class inputFileParser {

    private static Logger logger = LoggerFactory.getLogger(inputFileParser.class);
    public ArrayList<bcid> elementArrayList = new ArrayList();

    /**
     * Main method to demonstrate how this is used
     *
     * @param args
     */
    public static void main(String args[]) {

        /*
        String sampleInputStringFromTextBox = "" +

                "MBIO056\thttp://biocode.berkeley.edu/specimens/MBIO56\n" +
                "56\n";
        inputFileParser parse = null;
        try {
            parse = new inputFileParser(sampleInputStringFromTextBox, DATASET_ID);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (URISyntaxException e) {
            System.out.println("Invalid URI specified");
        }

        Iterator pi = parse.iterator();
        while (pi.hasNext()) {
            element b = (element)pi.next();
             System.out.println("sourceid = " + b.sourceID + ";webaddres = " + b.webAddress );
        }
        */

    }

    /**
     * Parse an input file and turn it into an Iterator containing elements
     *
     * @param inputString
     * @throws java.io.IOException
     * @throws java.net.URISyntaxException
     */
    public inputFileParser(String inputString, dataGroupMinter  dataset ) throws IOException, URISyntaxException {

        // TODO: check that user_id can write to dataset_id

        BufferedReader readbuffer = new BufferedReader(new StringReader(inputString));
        String strRead;
        while ((strRead = readbuffer.readLine()) != null) {
            String sourceID = null;
            URI webAddress = null;

            // Break string up into tokens, using pipe as the delimiter
            StringTokenizer st = new StringTokenizer(strRead, "|");
            int count = 0;
            while (st.hasMoreTokens()) {
                if (count == 0) {
                    sourceID = st.nextToken();
                } else if (count == 1) {
                    try {
                        webAddress = new URI(st.nextToken());
                    } catch (NullPointerException e) {
                        //TODO should we silence this exception?
                        logger.warn("NullPointerException for webAddress in the file: {}", inputString, e);
                        webAddress = null;
                    }
                }
                count++;
            }

            elementArrayList.add(new bcid(sourceID, webAddress, dataset.getDatasets_id()));
        }
    }

    /**
     * Return an iterator of element objects
     *
     * @return Iterator of BCIDs
     */
    public Iterator iterator() {
        return elementArrayList.iterator();
    }
}
