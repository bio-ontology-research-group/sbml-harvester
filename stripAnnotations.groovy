import java.util.logging.Logger
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.*
import org.semanticweb.owlapi.profiles.*
import org.semanticweb.owlapi.util.*
import org.semanticweb.owlapi.io.*

def diri = new File(args[0])

OWLOntologyManager manager = OWLManager.createOWLOntologyManager()
manager.addIRIMapper(new NonMappingOntologyIRIMapper())
manager.setSilentMissingImportsHandling(true)

OWLOntology ont = manager.loadOntologyFromOntologyDocument(diri)

OWLOntology ont2 = manager.createOntology(IRI.create("http://bioonto.de/test.owl"))

def s = ont.getAxioms()
s.each {
  if (it.isLogicalAxiom()) {
    manager.addAxiom(ont2,it)
  }
}

manager.saveOntology(ont2, IRI.create(new File(args[1]).toURI()))
