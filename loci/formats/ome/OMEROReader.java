//
// OMEROReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.ome;

import java.io.IOException;
import java.util.*;
import loci.formats.*;
import loci.formats.meta.MetadataStore;

/**
 * OMEROReader is the file format reader for downloading images from an
 * OMERO database.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/loci/formats/ome/OMEROReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/loci/formats/ome/OMEROReader.java">SVN</a></dd></dl>
 */
public class OMEROReader extends FormatReader {

  // -- Constants --

  private static final String NO_OMERO_MSG = "OMERO client libraries not " +
    "found.  Please install omero-common.jar and omero-client.jar from " +
    "http://www.loci.wisc.edu/ome/formats.html";

  // -- Static fields --

  private static boolean noOMERO = false;
  private static ReflectedUniverse r = createReflectedUniverse();

  private static ReflectedUniverse createReflectedUniverse() {
    r = null;
    try {
      r = new ReflectedUniverse();
      r.exec("import ome.api.IQuery");
      r.exec("import ome.api.RawPixelsStore");
      r.exec("import ome.parameters.Parameters");
      r.exec("import ome.system.Login");
      r.exec("import ome.system.Server");
      r.exec("import ome.system.ServiceFactory");
      r.exec("import pojos.ImageData");
      r.exec("import pojos.PixelsData");
    }
    catch (ReflectException e) {
      noOMERO = true;
      if (debug) LogTools.trace(e);
    }
    return r;
  }

  // -- Constructor --

  /** Constructs a new OMERO reader. */
  public OMEROReader() { super("OMERO", "*"); }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(String, boolean) */
  public boolean isThisType(String name, boolean open) {
    StringTokenizer st = new StringTokenizer(name, "\n");
    return st.countTokens() == 5;
  }

  /* @see loci.formats.IFormatReader#isThisType(byte[]) */
  public boolean isThisType(byte[] block) {
    return false;
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.assertId(currentId, true, 1);
    FormatTools.checkPlaneNumber(this, no);
    FormatTools.checkBufferSize(this, buf.length);

    int[] zct = FormatTools.getZCTCoords(this, no);
    try {
      r.setVar("z", new Integer(zct[0]));
      r.setVar("c", new Integer(zct[1]));
      r.setVar("t", new Integer(zct[2]));
      byte[] b = (byte[]) r.exec("raw.getPlane(z, c, t)");
      int bpp = FormatTools.getBytesPerPixel(getPixelType());
      for (int row=0; row<h; row++) {
        System.arraycopy(b, (row + y) * getSizeX() * bpp, buf, row * w * bpp,
          w * bpp);
      }
    }
    catch (ReflectException e) {
      throw new FormatException(e);
    }
    return buf;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    if (debug) debug("OMEROReader.initFile(" + id + ")");

    OMECredentials cred = OMEUtils.parseCredentials(id);
    id = String.valueOf(cred.imageID);
    super.initFile(id);

    cred.isOMERO = true;

    try {
      r.setVar("user", cred.username);
      r.setVar("pass", cred.password);
      r.setVar("port", Integer.parseInt(cred.port));
      r.setVar("sname", cred.server);
      r.setVar("id", cred.imageID);
      r.setVar("idObj", new Long(cred.imageID));

      r.exec("login = new Login(user, pass)");
      r.exec("server = new Server(sname, port)");
      r.exec("sf = new ServiceFactory(server, login)");
      r.exec("query = sf.getQueryService()");
      r.exec("raw = sf.createRawPixelsStore()");
      r.exec("raw.setPixelsId(id)");
      r.setVar("q", "select p from Pixels as p " +
        "left outer join fetch p.pixelsType as pt " +
        "left outer join fetch p.channels as c " +
        "left outer join fetch p.pixelsDimensions " +
        "left outer join fetch p.image " +
        "left outer join fetch c.colorComponent " +
        "left outer join fetch c.logicalChannel as lc " +
        "left outer join fetch c.statsInfo " +
        "left outer join fetch lc.photometricInterpretation " +
        "where p.id = :id");

      r.exec("params = new Parameters()");
      r.exec("params.addId(idObj)");
      r.exec("results = query.findByQuery(q, params)");
      r.exec("pix = new PixelsData(results)");

      core.sizeX[0] = ((Integer) r.exec("pix.getSizeX()")).intValue();
      core.sizeY[0] = ((Integer) r.exec("pix.getSizeY()")).intValue();
      core.sizeZ[0] = ((Integer) r.exec("pix.getSizeZ()")).intValue();
      core.sizeC[0] = ((Integer) r.exec("pix.getSizeC()")).intValue();
      core.sizeT[0] = ((Integer) r.exec("pix.getSizeT()")).intValue();
      core.pixelType[0] =
        FormatTools.pixelTypeFromString((String) r.exec("pix.getPixelType()"));

      core.rgb[0] = false;
      core.littleEndian[0] = false;
      core.currentOrder[0] = "XYZCT";
      core.imageCount[0] = getSizeZ() * getSizeC() * getSizeT();

      float px = ((Double) r.exec("pix.getPixelSizeX()")).floatValue();
      float py = ((Double) r.exec("pix.getPixelSizeY()")).floatValue();
      float pz = ((Double) r.exec("pix.getPixelSizeZ()")).floatValue();

      r.exec("image = pix.getImage()");
      r.exec("description = image.getDescription()");

      String name = (String) r.exec("image.getName()");
      String description = (String) r.exec("image.getDescription()");

      MetadataStore store = getMetadataStore();
      store.setImageName(name, 0);
      store.setImageDescription(description, 0);
      MetadataTools.populatePixels(store, this);

      store.setDimensionsPhysicalSizeX(new Float(px), 0, 0);
      store.setDimensionsPhysicalSizeY(new Float(py), 0, 0);
      store.setDimensionsPhysicalSizeZ(new Float(pz), 0, 0);
      // CTR CHECK
//      for (int i=0; i<core.sizeC[0]; i++) {
//        store.setLogicalChannel(i, null, null, null, null, null, null, null,
//          null, null, null, null, null, null, null, null, null, null, null,
//          null, null, null, null, null, null);
//      }
    }
    catch (ReflectException e) {
      throw new FormatException(e);
    }
  }
}
